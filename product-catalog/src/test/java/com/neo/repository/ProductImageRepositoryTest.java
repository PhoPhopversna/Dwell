package com.neo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.neo.entity.Category;
import com.neo.entity.Product;
import com.neo.entity.ProductImage;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

class ProductImageRepositoryTest extends AbstractRepositoryTest {

  @Autowired private ProductImageRepository productImageRepository;
  @Autowired private TestEntityManager em;

  private ProductImage image;

  @BeforeEach
  void setUp() {
    Category category = em.persist(new Category("Laptops", null));
    Product product = new Product("LP-1", "Laptop", category, BigDecimal.TEN);
    product.addImage(new ProductImage("http://minio/images/a.png", 0));
    em.persist(product);
    image = product.getImages().get(0);
    em.flush();
    em.clear();
  }

  @Test
  void newImagesStartInProcessingState() {
    ProductImage found = productImageRepository.findById(image.getId()).orElseThrow();

    assertThat(found.getStatus()).isEqualTo(ProductImage.ImageStatus.PROCESSING);
    assertThat(found.getThumbnailUrl()).isNull();
  }

  @Test
  void markReadyUpdatesThumbnailAndStatus() {
    int updated =
        productImageRepository.markReady(
            image.getId(), "http://minio/images/thumb-a.png", ProductImage.ImageStatus.READY);
    em.clear();

    assertThat(updated).isEqualTo(1);
    ProductImage found = productImageRepository.findById(image.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(ProductImage.ImageStatus.READY);
    assertThat(found.getThumbnailUrl()).isEqualTo("http://minio/images/thumb-a.png");
  }

  @Test
  void markReadyReturnsZeroWhenImageNoLongerExists() {
    int updated =
        productImageRepository.markReady(
            UUID.randomUUID(), "http://minio/images/thumb-x.png", ProductImage.ImageStatus.READY);

    assertThat(updated).isZero();
  }

  @Test
  void existsByIdReflectsDeletion() {
    assertThat(productImageRepository.existsById(image.getId())).isTrue();

    productImageRepository.deleteById(image.getId());
    em.flush();

    assertThat(productImageRepository.existsById(image.getId())).isFalse();
  }

  @Test
  void findByProductIdOrderByPositionAscReturnsGalleryOrder() {
    Product product = em.find(Product.class, image.getProduct().getId());
    product.addImage(new ProductImage("http://minio/images/c.png", 2));
    product.addImage(new ProductImage("http://minio/images/b.png", 1));
    em.flush();
    em.clear();

    assertThat(productImageRepository.findByProductIdOrderByPositionAsc(product.getId()))
        .extracting(ProductImage::getPosition)
        .containsExactly(0, 1, 2);
  }
}
