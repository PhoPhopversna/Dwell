package com.neo.service.impl;

import com.neo.dto.ProductRequest;
import com.neo.entity.Category;
import com.neo.entity.Product;
import com.neo.entity.ProductImage;
import com.neo.event.ProductImageUploadedEvent;
import com.neo.mapper.ProductMapper;
import com.neo.repository.CategoryRepository;
import com.neo.repository.ProductImageRepository;
import com.neo.repository.ProductRepository;
import com.neo.service.KafkaPublisher;
import com.neo.service.ProductService;
import com.neo.service.validator.ProductAttributeValidator;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

  private final MinioClient minioClient;
  private final ProductRepository productRepository;
  private final ProductImageRepository productImageRepository;
  private final CategoryRepository categoryRepository;
  private final KafkaPublisher kafkaPublisher;

  private final ProductMapper productMapper;

  private final ProductAttributeValidator productAttributeValidator;

  @Value("${minio.bucket}")
  private String bucket;

  @Value("${minio.url}")
  private String minioUrl;

  public ProductServiceImpl(
      MinioClient minioClient,
      ProductRepository productRepository,
      ProductImageRepository productImageRepository,
      CategoryRepository categoryRepository,
      KafkaPublisher kafkaPublisher,
      ProductAttributeValidator productAttributeValidator,
      ProductMapper productMapper) {
    this.minioClient = minioClient;
    this.productRepository = productRepository;
    this.productImageRepository = productImageRepository;
    this.categoryRepository = categoryRepository;
    this.kafkaPublisher = kafkaPublisher;
    this.productAttributeValidator = productAttributeValidator;
    this.productMapper = productMapper;
  }

  @Override
  @Transactional
  public Product createProduct(ProductRequest req, List<MultipartFile> images) throws Exception {
    Category category =
        categoryRepository
            .findById(req.getCategoryId())
            .orElseThrow(
                () -> new EntityNotFoundException("Category not found: " + req.getCategoryId()));
    productAttributeValidator.validate(category, req.getAttributes());
    Product product = productMapper.toEntity(req);
    product.setCategory(category);
    product = productRepository.save(product);
    log.debug("Saved Products : {}", product);
    for (MultipartFile image : images) {
      addImageToProduct(product.getId(), image);
    }
    return null;
  }

  @Override
  @Transactional
  public ProductImage addImageToProduct(UUID productId, MultipartFile file) throws Exception {
    Product product =
        productRepository
            .findById(productId)
            .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));

    int nextPosition = product.getImages().size();
    ProductImage productImage = uploadAndAttachImage(product, file, nextPosition);
    publishImageUploadedEvent(product, productImage);
    return productImage;
  }

  private ProductImage uploadAndAttachImage(Product product, MultipartFile file, int position)
      throws Exception {
    String objectName = UUID.randomUUID() + "-" + file.getOriginalFilename();

    log.info(
        "Starting upload - Bucket: {}, Object: {}, Size: {}",
        bucket,
        objectName,
        file.getSize());
    try (InputStream is = file.getInputStream()) {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(objectName).stream(is, file.getSize(), -1)
              .contentType(file.getContentType())
              .build());

      log.info("Upload successful for: {}", objectName);
    }
    try {
      minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectName).build());
      log.info("✓ File verified in bucket");
    } catch (ErrorResponseException e) {
      log.error("❌ File verification failed: {}", e.getMessage());
      throw new RuntimeException("File upload verification failed");
    }
    String imageUrl = minioUrl + "/" + bucket + "/" + objectName;
    log.error("URL: {}", imageUrl);
    ProductImage productImage = new ProductImage(imageUrl, position);
    productImage.setProduct(product);
    product.getImages().add(productImage);
    return productImageRepository.save(productImage);
  }

  private void publishImageUploadedEvent(Product product, ProductImage productImage) {
    String objectName = productImage.getUrl().substring(productImage.getUrl().lastIndexOf('/') + 1);

    ProductImageUploadedEvent event =
        new ProductImageUploadedEvent(productImage.getId(), product.getId(), objectName);

    // was: kafkaTemplate.send(topic, key, event)
    // now: goes through the dynamic topic registry by logical key
    kafkaPublisher.publishImageUploaded(productImage.getId().toString(), event);
  }
}
