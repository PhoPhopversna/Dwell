package com.neo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.neo.entity.AttributeDefinition;
import com.neo.entity.Category;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

class CategoryRepositoryTest extends AbstractRepositoryTest {

  @Autowired private CategoryRepository categoryRepository;
  @Autowired private TestEntityManager em;

  @Test
  void findByUuidReturnsSavedCategoryWithParent() {
    Category parent = em.persist(new Category("Electronics", null));
    Category child = em.persist(new Category("Laptops", parent));
    em.flush();
    em.clear();

    Category found = categoryRepository.findById(child.getId()).orElseThrow();

    assertThat(found.getName()).isEqualTo("Laptops");
    assertThat(found.getParent().getId()).isEqualTo(parent.getId());
  }

  @Test
  void findByUnknownUuidIsEmpty() {
    assertThat(categoryRepository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findAllReturnsEveryCategory() {
    em.persist(new Category("A", null));
    em.persist(new Category("B", null));

    assertThat(categoryRepository.findAll())
        .extracting(Category::getName)
        .containsExactlyInAnyOrder("A", "B");
  }

  @Test
  void attributeDefinitionsCascadeAndPersistJsonbOptions() {
    Category category = new Category("Shirts", null);
    AttributeDefinition size =
        new AttributeDefinition(category, "size", "Size", AttributeDefinition.DataType.SELECT);
    size.setOptions(List.of("S", "M", "L"));
    size.setRequired(true);
    category.getAttributeDefinitions().add(size);

    UUID id = categoryRepository.save(category).getId();
    em.flush();
    em.clear();

    Category found = categoryRepository.findById(id).orElseThrow();
    assertThat(found.getAttributeDefinitions())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.getKey()).isEqualTo("size");
              assertThat(d.getOptions()).containsExactly("S", "M", "L");
              assertThat(d.isRequired()).isTrue();
            });
  }

  @Test
  void removingAttributeDefinitionDeletesOrphan() {
    Category category = new Category("Shirts", null);
    category
        .getAttributeDefinitions()
        .add(new AttributeDefinition(category, "color", "Color", AttributeDefinition.DataType.TEXT));
    UUID id = categoryRepository.save(category).getId();
    em.flush();

    category.getAttributeDefinitions().clear();
    em.flush();
    em.clear();

    assertThat(categoryRepository.findById(id).orElseThrow().getAttributeDefinitions()).isEmpty();
    assertThat(
            em.getEntityManager()
                .createQuery("select count(a) from AttributeDefinition a", Long.class)
                .getSingleResult())
        .isZero();
  }

  @Test
  void builtInIdMethodsUseUuid() {
    UUID id = em.persistAndFlush(new Category("Temp", null)).getId();

    assertThat(categoryRepository.existsById(id)).isTrue();
    categoryRepository.deleteById(id);
    em.flush();

    assertThat(categoryRepository.existsById(id)).isFalse();
  }
}
