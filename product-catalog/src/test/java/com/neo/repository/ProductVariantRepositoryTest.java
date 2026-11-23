package com.neo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.neo.entity.Category;
import com.neo.entity.Product;
import com.neo.entity.ProductVariant;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

class ProductVariantRepositoryTest extends AbstractRepositoryTest {

  @Autowired private ProductVariantRepository repository;
  @Autowired private TestEntityManager em;

  private Product shirt;
  private Product pants;
  private ProductVariant shirtM;

  @BeforeEach
  void setUp() {
    Category category = em.persist(new Category("Clothes", null));
    shirt = em.persist(new Product("SH-1", "Shirt", category, new BigDecimal("20.00")));
    pants = em.persist(new Product("PA-1", "Pants", category, new BigDecimal("30.00")));
    shirtM = new ProductVariant(shirt, "SH-1-M", Map.of("size", "M"));
    shirtM.setPriceOverride(new BigDecimal("22.50"));
    shirtM.setBarcode("0001");
    em.persist(shirtM);
    em.persist(new ProductVariant(pants, "PA-1-32", Map.of("waist", 32)));
    em.flush();
    em.clear();
  }

  @Test
  void findByProductIdReturnsOnlyThatProductsVariants() {
    assertThat(repository.findByProductId(shirt.getId()))
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.getSku()).isEqualTo("SH-1-M");
              assertThat(v.getVariantAttributes()).containsEntry("size", "M");
              assertThat(v.getPriceOverride()).isEqualByComparingTo("22.50");
              assertThat(v.getBarcode()).isEqualTo("0001");
            });
  }

  @Test
  void findByIdAndProductIdScopesToProduct() {
    assertThat(repository.findByIdAndProductId(shirtM.getId(), shirt.getId())).isPresent();
    assertThat(repository.findByIdAndProductId(shirtM.getId(), pants.getId())).isEmpty();
  }

  @Test
  void existsBySku() {
    assertThat(repository.existsBySku("SH-1-M")).isTrue();
    assertThat(repository.existsBySku("NOPE")).isFalse();
  }
}
