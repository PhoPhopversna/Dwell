package com.neo.service.impl;

import com.neo.dto.ProductRequest;
import com.neo.entity.Category;
import com.neo.entity.Product;
import com.neo.event.ProductImageDeletedEvent;
import com.neo.mapper.ProductMapper;
import com.neo.repository.CategoryRepository;
import com.neo.repository.ProductRepository;
import com.neo.service.KafkaPublisher;
import com.neo.service.ProductImageService;
import com.neo.service.ProductService;
import com.neo.service.validator.ImageFileValidator;
import com.neo.service.validator.ProductAttributeValidator;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

  private final ProductRepository productRepository;
  private final CategoryRepository categoryRepository;
  private final ProductImageService productImageService;
  private final KafkaPublisher kafkaPublisher;

  private final ProductMapper productMapper;

  private final ProductAttributeValidator productAttributeValidator;

  private final TransactionTemplate transactionTemplate;

  public ProductServiceImpl(
      ProductRepository productRepository,
      CategoryRepository categoryRepository,
      ProductImageService productImageService,
      KafkaPublisher kafkaPublisher,
      ProductAttributeValidator productAttributeValidator,
      ProductMapper productMapper,
      TransactionTemplate transactionTemplate) {
    this.productRepository = productRepository;
    this.categoryRepository = categoryRepository;
    this.productImageService = productImageService;
    this.kafkaPublisher = kafkaPublisher;
    this.productAttributeValidator = productAttributeValidator;
    this.productMapper = productMapper;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  @Transactional
  public Product createProduct(ProductRequest req, List<MultipartFile> images) throws Exception {
    ImageFileValidator.validate(images); // before anything is saved or uploaded
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
      productImageService.addImageToProduct(product.getId(), image);
    }
    return product;
  }

  @Override
  @Transactional
  public Product updateProduct(UUID productId, ProductRequest req, List<MultipartFile> images)
      throws Exception {
    ImageFileValidator.validate(images); // before anything is saved or uploaded
    Category category =
        categoryRepository
            .findById(req.getCategoryId())
            .orElseThrow(
                () -> new EntityNotFoundException("Category not found: " + req.getCategoryId()));
    Product product =
        productRepository
            .findById(productId)
            .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
    productAttributeValidator.validate(category, req.getAttributes());
    Product productRequest = productMapper.toEntity(req);
    product.setName(productRequest.getName());
    product.setCategory(category);
    product.setAttributes(productRequest.getAttributes());
    product.setSku(productRequest.getSku());
    product.setBasePrice(productRequest.getBasePrice());
    product.setCostPrice(productRequest.getCostPrice());
    // Variants are not mapped from the request (ProductMapper ignores them), so leave the
    // existing collection untouched. Replacing it would make Hibernate fail on the
    // orphanRemoval collection and would delete every existing variant.
    product.setStatus(productRequest.getStatus());
    log.debug("Saved Products : {}", product);
    for (MultipartFile image : images) {
      productImageService.addImageToProduct(product.getId(), image);
    }
    return productRepository.save(product);
  }

  @Override
  public void deleteProduct(UUID productId) {
    // Delete the row (images and variants cascade) inside a transaction, then publish the MinIO
    // cleanup events only after it commits so files are never removed for a rolled-back delete.
    List<ProductImageDeletedEvent> events =
        transactionTemplate.execute(
            status -> {
              Product product =
                  productRepository
                      .findById(productId)
                      .orElseThrow(
                          () -> new EntityNotFoundException("Product not found: " + productId));
              List<ProductImageDeletedEvent> imageEvents =
                  product.getImages().stream()
                      .map(i -> ProductImageServiceImpl.toDeletedEvent(i, productId))
                      .toList();
              productRepository.delete(product);
              return imageEvents;
            });

    events.forEach(e -> kafkaPublisher.deleteImage(e.productImageId().toString(), e));
  }

  @Override
  @Transactional
  public Product getProductById(UUID productId) {
    return productRepository
        .findById(productId)
        .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
  }

  @Override
  @Transactional
  public List<Product> getProducts() {
    return productRepository.findAll();
  }
}
