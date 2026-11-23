package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neo.dto.ProductRequest;
import com.neo.entity.Category;
import com.neo.entity.Product;
import com.neo.entity.ProductImage;
import com.neo.entity.ProductVariant;
import com.neo.event.ProductImageDeletedEvent;
import com.neo.exception.AttributeValidationException;
import com.neo.mapper.ProductMapper;
import com.neo.repository.CategoryRepository;
import com.neo.repository.ProductRepository;
import com.neo.service.KafkaPublisher;
import com.neo.service.ProductImageService;
import com.neo.service.validator.ProductAttributeValidator;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

  @Mock private ProductRepository productRepository;
  @Mock private CategoryRepository categoryRepository;
  @Mock private ProductImageService productImageService;
  @Mock private KafkaPublisher kafkaPublisher;
  @Mock private ProductAttributeValidator productAttributeValidator;
  @Mock private ProductMapper productMapper;
  @Mock private TransactionTemplate transactionTemplate;
  @InjectMocks private ProductServiceImpl service;

  private Category category;

  @BeforeEach
  void setUp() {
    category = new Category("Laptops", null);
    category.setId(UUID.randomUUID());
  }

  private ProductRequest request() {
    ProductRequest r = new ProductRequest();
    r.setSku("LP-14");
    r.setName("Laptop");
    r.setCategoryId(category.getId());
    r.setBasePrice(new BigDecimal("10.00"));
    r.setAttributes(Map.of("color", "silver"));
    return r;
  }

  private static Product persistedProduct(Category category) {
    Product p = new Product("LP-14", "Laptop", category, new BigDecimal("10.00"));
    p.setId(UUID.randomUUID());
    return p;
  }

  private static MultipartFile image(String name) {
    return new MockMultipartFile("images", name, "image/png", new byte[] {1, 2, 3});
  }

  @Nested
  class CreateProduct {

    @Test
    void validatesMapsSavesAndUploadsEachImage() throws Exception {
      ProductRequest req = request();
      Product mapped = new Product();
      Product saved = persistedProduct(category);
      MultipartFile a = image("a.png");
      MultipartFile b = image("b.png");
      when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
      when(productMapper.toEntity(req)).thenReturn(mapped);
      when(productRepository.save(mapped)).thenReturn(saved);

      Product result = service.createProduct(req, List.of(a, b));

      assertThat(result).isSameAs(saved);
      assertThat(mapped.getCategory()).isSameAs(category);
      verify(productAttributeValidator).validate(category, req.getAttributes());
      var order = inOrder(productImageService);
      order.verify(productImageService).addImageToProduct(saved.getId(), a);
      order.verify(productImageService).addImageToProduct(saved.getId(), b);
    }

    @Test
    void failsWhenCategoryMissing() {
      when(categoryRepository.findById(category.getId())).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.createProduct(request(), List.of()))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessageContaining("Category not found");
      verifyNoInteractions(productRepository, productAttributeValidator, productImageService);
    }

    @Test
    void attributeValidationFailureSavesNothing() {
      when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
      doThrow(new AttributeValidationException(List.of("bad"), HttpStatus.BAD_REQUEST))
          .when(productAttributeValidator)
          .validate(any(), anyMap());

      assertThatThrownBy(() -> service.createProduct(request(), List.of()))
          .isInstanceOf(AttributeValidationException.class);
      verify(productRepository, never()).save(any());
      verifyNoInteractions(productImageService);
    }
  }

  @Nested
  class GetProducts {

    @Test
    void getByIdReturnsProduct() {
      Product product = persistedProduct(category);
      when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

      assertThat(service.getProductById(product.getId())).isSameAs(product);
    }

    @Test
    void getByIdThrowsWhenMissing() {
      UUID id = UUID.randomUUID();
      when(productRepository.findById(id)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.getProductById(id))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessageContaining(id.toString());
    }

    @Test
    void getProductsReturnsAll() {
      List<Product> all = List.of(persistedProduct(category), persistedProduct(category));
      when(productRepository.findAll()).thenReturn(all);

      assertThat(service.getProducts()).isEqualTo(all);
    }
  }

  @Nested
  class UpdateProduct {

    /** Mirrors the real mapper for the fields updateProduct copies across. */
    private Product mapped(ProductRequest req) {
      Product p = new Product(req.getSku(), req.getName(), null, req.getBasePrice());
      p.setCostPrice(req.getCostPrice());
      p.setStatus(req.getStatus() != null ? req.getStatus() : Product.Status.DRAFT);
      p.setAttributes(new HashMap<>(req.getAttributes()));
      return p;
    }

    private Product stubExisting(ProductRequest req, Category target) {
      Product existing = persistedProduct(category);
      when(categoryRepository.findById(target.getId())).thenReturn(Optional.of(target));
      when(productRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
      when(productMapper.toEntity(req)).thenReturn(mapped(req));
      when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
      return existing;
    }

    @Test
    void copiesAllRequestFieldsOntoExistingProduct() throws Exception {
      Category newCategory = new Category("Tablets", null);
      newCategory.setId(UUID.randomUUID());
      ProductRequest req = request();
      req.setCategoryId(newCategory.getId());
      req.setName("Renamed");
      req.setSku("LP-NEW");
      req.setBasePrice(new BigDecimal("42.00"));
      req.setCostPrice(new BigDecimal("30.00"));
      req.setStatus(Product.Status.ACTIVE);
      req.setAttributes(Map.of("color", "black"));
      Product existing = stubExisting(req, newCategory);
      UUID originalId = existing.getId();

      Product result = service.updateProduct(originalId, req, List.of());

      assertThat(result).isSameAs(existing);
      assertThat(result.getId()).isEqualTo(originalId);
      assertThat(result.getName()).isEqualTo("Renamed");
      assertThat(result.getCategory()).isSameAs(newCategory);
      assertThat(result.getSku()).isEqualTo("LP-NEW");
      assertThat(result.getBasePrice()).isEqualByComparingTo("42.00");
      assertThat(result.getCostPrice()).isEqualByComparingTo("30.00");
      assertThat(result.getStatus()).isEqualTo(Product.Status.ACTIVE);
      assertThat(result.getAttributes()).containsExactlyEntriesOf(Map.of("color", "black"));
      verify(productAttributeValidator).validate(newCategory, req.getAttributes());
    }

    @Test
    void uploadsNewImagesAndKeepsExistingVariants() throws Exception {
      ProductRequest req = request();
      Product existing = stubExisting(req, category);
      existing.addVariant(new ProductVariant(existing, "LP-14-SLV", Map.of()));
      var variants = existing.getVariants();
      MultipartFile file = image("n.png");

      Product result = service.updateProduct(existing.getId(), req, List.of(file));

      verify(productImageService).addImageToProduct(existing.getId(), file);
      assertThat(result.getVariants()).isSameAs(variants).hasSize(1);
    }

    @Test
    void failsWhenProductMissing() {
      UUID id = UUID.randomUUID();
      when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
      when(productRepository.findById(id)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.updateProduct(id, request(), List.of()))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessageContaining("Product not found");
      verifyNoInteractions(productAttributeValidator);
    }
  }

  @Nested
  class DeleteProduct {

    @BeforeEach
    void runTransactionsInline() {
      when(transactionTemplate.execute(any()))
          .thenAnswer(
              inv ->
                  inv.<TransactionCallback<?>>getArgument(0)
                      .doInTransaction(mock(TransactionStatus.class)));
    }

    @Test
    void deletesProductThenPublishesCleanupEventPerImage() {
      Product product = persistedProduct(category);
      ProductImage a = new ProductImage("http://minio/images/a.png", 0);
      ProductImage b = new ProductImage("http://minio/images/b.png", 1);
      a.setId(UUID.randomUUID());
      b.setId(UUID.randomUUID());
      product.addImage(a);
      product.addImage(b);
      when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

      service.deleteProduct(product.getId());

      var order = inOrder(productRepository, kafkaPublisher);
      order.verify(productRepository).delete(product);
      order
          .verify(kafkaPublisher)
          .deleteImage(
              eq(a.getId().toString()),
              eq(new ProductImageDeletedEvent(a.getId(), product.getId(), "a.png", "thumb-a.png")));
      order
          .verify(kafkaPublisher)
          .deleteImage(
              eq(b.getId().toString()),
              eq(new ProductImageDeletedEvent(b.getId(), product.getId(), "b.png", "thumb-b.png")));
    }

    @Test
    void missingProductThrowsAndPublishesNothing() {
      UUID id = UUID.randomUUID();
      when(productRepository.findById(id)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.deleteProduct(id))
          .isInstanceOf(EntityNotFoundException.class);
      verify(productRepository, never()).delete(any(Product.class));
      verifyNoInteractions(kafkaPublisher);
    }
  }
}
