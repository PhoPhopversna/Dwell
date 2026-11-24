package com.neo.service.impl;

import com.neo.dto.ProductImageResponse;
import com.neo.entity.Product;
import com.neo.entity.ProductImage;
import com.neo.event.ProductImageDeletedEvent;
import com.neo.event.ProductImageUploadedEvent;
import com.neo.exception.AppException;
import com.neo.mapper.ProductMapper;
import com.neo.repository.ProductImageRepository;
import com.neo.repository.ProductRepository;
import com.neo.service.KafkaPublisher;
import com.neo.service.ProductImageService;
import com.neo.service.validator.ImageFileValidator;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.MinioException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class ProductImageServiceImpl implements ProductImageService {

  private final MinioClient minioClient;
  private final ProductRepository productRepository;
  private final ProductImageRepository productImageRepository;
  private final KafkaPublisher kafkaPublisher;
  private final ProductMapper productMapper;
  private final TransactionTemplate transactionTemplate;

  @Value("${minio.bucket}")
  private String bucket;

  @Value("${minio.url}")
  private String minioUrl;

  public ProductImageServiceImpl(
      MinioClient minioClient,
      ProductRepository productRepository,
      ProductImageRepository productImageRepository,
      KafkaPublisher kafkaPublisher,
      ProductMapper productMapper,
      TransactionTemplate transactionTemplate) {
    this.minioClient = minioClient;
    this.productRepository = productRepository;
    this.productImageRepository = productImageRepository;
    this.kafkaPublisher = kafkaPublisher;
    this.productMapper = productMapper;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  @Transactional
  public ProductImageResponse addImageToProduct(UUID productId, MultipartFile file)
      throws Exception {
    ImageFileValidator.validate(file);
    Product product = findProduct(productId);

    String objectName =
        UUID.randomUUID() + "-" + ImageFileValidator.safeFileName(file.getOriginalFilename());
    try (InputStream is = file.getInputStream()) {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(objectName).stream(is, file.getSize(), -1)
              .contentType(file.getContentType())
              .build());
      // Throws if the object is missing, aborting the transaction before the row is saved
      minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectName).build());
    } catch (MinioException | GeneralSecurityException | IOException e) {
      throw new AppException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "IMAGE_STORAGE_UNAVAILABLE",
          "Failed to store image %s in bucket %s: %s".formatted(objectName, bucket, e.getMessage()),
          e);
    }
    log.info("Uploaded {} to bucket {}", objectName, bucket);

    ProductImage image =
        new ProductImage(minioUrl + "/" + bucket + "/" + objectName, product.getImages().size());
    product.addImage(image);
    ProductImage saved = productImageRepository.save(image);

    // Publish only after commit: the consumer skips rows it can't see
    ProductImageUploadedEvent event =
        new ProductImageUploadedEvent(saved.getId(), productId, objectName);
    afterCommit(() -> kafkaPublisher.publishImageUploaded(saved.getId().toString(), event));
    return productMapper.toResponse(saved);
  }

  @Override
  @Transactional
  public List<ProductImageResponse> getImages(UUID productId) {
    if (!productRepository.existsById(productId)) {
      throw new EntityNotFoundException("Product not found: " + productId);
    }
    return productImageRepository.findByProductIdOrderByPositionAsc(productId).stream()
        .map(productMapper::toResponse)
        .toList();
  }

  @Override
  @Transactional
  public ProductImageResponse updatePosition(UUID productId, UUID imageId, int position) {
    Product product = findProduct(productId);
    List<ProductImage> gallery = new ArrayList<>(product.getImages());
    ProductImage image =
        gallery.stream()
            .filter(i -> i.getId().equals(imageId))
            .findFirst()
            .orElseThrow(() -> new EntityNotFoundException("Image not found: " + imageId));

    gallery.remove(image);
    gallery.add(Math.min(position, gallery.size()), image);
    renumber(gallery);
    return productMapper.toResponse(image);
  }

  @Override
  public void deleteImage(UUID productId, UUID imageId) {
    ProductImageDeletedEvent event =
        transactionTemplate.execute(
            status -> {
              Product product = findProduct(productId);
              ProductImage image =
                  product.getImages().stream()
                      .filter(i -> i.getId().equals(imageId))
                      .findFirst()
                      .orElseThrow(
                          () -> new EntityNotFoundException("Image not found: " + imageId));

              ProductImageDeletedEvent e = toDeletedEvent(image, productId);
              product.getImages().remove(image);
              productImageRepository.delete(image);
              // Close the gap so the next position (images.size()) stays unique
              renumber(product.getImages());
              return e;
            });

    // After the transaction, so files are never removed for a rolled-back delete
    kafkaPublisher.deleteImage(imageId.toString(), event);
  }

  static ProductImageDeletedEvent toDeletedEvent(ProductImage image, UUID productId) {
    String objectName = image.getUrl().substring(image.getUrl().lastIndexOf('/') + 1);
    return new ProductImageDeletedEvent(
        image.getId(), productId, objectName, "thumb-" + objectName);
  }

  private Product findProduct(UUID productId) {
    return productRepository
        .findById(productId)
        .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
  }

  private static void renumber(List<ProductImage> images) {
    for (int i = 0; i < images.size(); i++) {
      images.get(i).setPosition(i);
    }
  }

  private static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }
}
