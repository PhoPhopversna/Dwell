package com.neo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neo.entity.Category;
import com.neo.entity.Product;
import com.neo.entity.ProductImage;
import com.neo.entity.ProductVariant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

class ProductRepositoryTest extends AbstractRepositoryTest {

  @Autowired private ProductRepository productRepository;
  @Autowired private TestEntityManager em;

  private Category category;

  @BeforeEach
  void setUp() {
    category = em.persist(new Category("Laptops", null));
  }

  @Test
  void savePersistsFieldsJsonbAttributesAndTimestamps() {
    Product product = new Product("LP-14", "Laptop", category, new BigDecimal("1299.99"));
    product.setStatus(Product.Status.ACTIVE);
    product.setAttribute("color", "silver");
    product.setAttribute("ram_gb", 16);
    product.setAttribute("tags", List.of("a", "b"));

    UUID id = productRepository.save(product).getId();
    em.flush();
    em.clear();

    Product found = productRepository.findById(id).orElseThrow();
    assertThat(found.getSku()).isEqualTo("LP-14");
    assertThat(found.getBasePrice()).isEqualByComparingTo("1299.99");
    assertThat(found.getStatus()).isEqualTo(Product.Status.ACTIVE);
    assertThat(found.getCategory().getId()).isEqualTo(category.getId());
    assertThat(found.getAttributes())
        .containsEntry("color", "silver")
        .containsEntry("ram_gb", 16)
        .containsEntry("tags", List.of("a", "b"));
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.getUpdatedAt()).isNotNull();
  }

  @Test
  void variantsAndImagesCascadeAndImagesAreOrderedByPosition() {
    Product product = new Product("TS-1", "T-Shirt", category, new BigDecimal("20.00"));
    product.addVariant(new ProductVariant(product, "TS-1-M", Map.of("size", "M")));
    product.addImage(new ProductImage("http://minio/images/second.png", 1));
    product.addImage(new ProductImage("http://minio/images/first.png", 0));

    UUID id = productRepository.save(product).getId();
    em.flush();
    em.clear();

    Product found = productRepository.findById(id).orElseThrow();
    assertThat(found.getVariants())
        .singleElement()
        .satisfies(v -> assertThat(v.getVariantAttributes()).containsEntry("size", "M"));
    assertThat(found.getImages()).extracting(ProductImage::getPosition).containsExactly(0, 1);
  }

  @Test
  void deletingProductCascadesToImagesAndVariants() {
    Product product = new Product("DEL-1", "Gone", category, BigDecimal.ONE);
    product.addVariant(new ProductVariant(product, "DEL-1-V", Map.of()));
    product.addImage(new ProductImage("http://minio/images/x.png", 0));
    productRepository.save(product);
    em.flush();

    productRepository.delete(product);
    em.flush();

    assertThat(count("ProductImage")).isZero();
    assertThat(count("ProductVariant")).isZero();
  }

  @Test
  void duplicateSkuViolatesUniqueConstraint() {
    productRepository.saveAndFlush(new Product("DUP", "One", category, BigDecimal.ONE));

    assertThatThrownBy(
            () ->
                productRepository.saveAndFlush(new Product("DUP", "Two", category, BigDecimal.ONE)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private long count(String entity) {
    return em.getEntityManager()
        .createQuery("select count(e) from " + entity + " e", Long.class)
        .getSingleResult();
  }
}
