package com.neo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neo.entity.AttributeDefinition;
import com.neo.entity.AttributeDefinition.DataType;
import com.neo.entity.Category;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

class AttributeDefinitionRepositoryTest extends AbstractRepositoryTest {

  @Autowired private AttributeDefinitionRepository repository;
  @Autowired private TestEntityManager em;

  private Category shirts;
  private Category shoes;
  private AttributeDefinition shirtSize;

  @BeforeEach
  void setUp() {
    shirts = em.persist(new Category("Shirts", null));
    shoes = em.persist(new Category("Shoes", null));
    shirtSize = em.persist(new AttributeDefinition(shirts, "size", "Size", DataType.TEXT));
    em.persist(new AttributeDefinition(shirts, "color", "Color", DataType.TEXT));
    em.persist(new AttributeDefinition(shoes, "size", "Size", DataType.NUMBER));
    em.flush();
  }

  @Test
  void findByCategoryIdReturnsOnlyThatCategory() {
    assertThat(repository.findByCategoryId(shirts.getId()))
        .extracting(AttributeDefinition::getKey)
        .containsExactlyInAnyOrder("size", "color");
  }

  @Test
  void findByIdAndCategoryIdScopesToCategory() {
    assertThat(repository.findByIdAndCategoryId(shirtSize.getId(), shirts.getId())).isPresent();
    assertThat(repository.findByIdAndCategoryId(shirtSize.getId(), shoes.getId())).isEmpty();
    assertThat(repository.findByIdAndCategoryId(UUID.randomUUID(), shirts.getId())).isEmpty();
  }

  @Test
  void existsByCategoryIdAndKeyIsPerCategory() {
    assertThat(repository.existsByCategoryIdAndKey(shirts.getId(), "color")).isTrue();
    assertThat(repository.existsByCategoryIdAndKey(shoes.getId(), "color")).isFalse();
  }

  @Test
  void sameKeyTwiceInOneCategoryViolatesUniqueConstraint() {
    assertThatThrownBy(
            () ->
                repository.saveAndFlush(
                    new AttributeDefinition(shirts, "size", "Dup", DataType.TEXT)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
