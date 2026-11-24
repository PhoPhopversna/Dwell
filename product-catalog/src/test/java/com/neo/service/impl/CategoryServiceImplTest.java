package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neo.dto.CategoryRequest;
import com.neo.dto.CategoryResponse;
import com.neo.entity.Category;
import com.neo.exception.AppException;
import com.neo.mapper.CategoryMapper;
import com.neo.repository.CategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

  @Mock private CategoryRepository categoryRepository;
  @Mock private CategoryMapper categoryMapper;
  @InjectMocks private CategoryServiceImpl service;

  private static Category category(String name, Category parent) {
    Category c = new Category(name, parent);
    c.setId(UUID.randomUUID());
    if (parent != null) {
      parent.getChildren().add(c);
    }
    return c;
  }

  private static CategoryRequest request(String name, UUID parentId) {
    CategoryRequest r = new CategoryRequest();
    r.setName(name);
    r.setParentId(parentId);
    return r;
  }

  private void stubToResponse() {
    when(categoryMapper.toResponse(any()))
        .thenAnswer(
            inv -> {
              Category c = inv.getArgument(0);
              CategoryResponse r = new CategoryResponse();
              r.setId(c.getId());
              r.setName(c.getName());
              r.setParentId(c.getParent() != null ? c.getParent().getId() : null);
              return r;
            });
  }

  @Nested
  class GetCategories {
    @Test
    void mapsEveryCategory() {
      Category a = category("A", null);
      Category b = category("B", null);
      when(categoryRepository.findAll()).thenReturn(List.of(a, b));
      stubToResponse();

      List<CategoryResponse> result = service.getCategories();

      assertThat(result).extracting(CategoryResponse::getName).containsExactly("A", "B");
    }

    @Test
    void emptyRepositoryReturnsEmptyList() {
      when(categoryRepository.findAll()).thenReturn(List.of());

      assertThat(service.getCategories()).isEmpty();
    }
  }

  @Nested
  class GetCategoryById {
    @Test
    void returnsMappedCategory() {
      Category a = category("A", null);
      when(categoryRepository.findById(a.getId())).thenReturn(Optional.of(a));
      stubToResponse();

      assertThat(service.getCategoryById(a.getId()).getId()).isEqualTo(a.getId());
    }

    @Test
    void throwsWhenMissing() {
      UUID id = UUID.randomUUID();
      when(categoryRepository.findById(id)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.getCategoryById(id))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessageContaining(id.toString());
    }
  }

  @Nested
  class SaveCategory {
    @Test
    void savesRootCategory() {
      CategoryRequest req = request("Root", null);
      Category mapped = new Category("Root", null);
      when(categoryMapper.toEntity(req)).thenReturn(mapped);
      when(categoryRepository.save(mapped)).thenReturn(mapped);
      stubToResponse();

      CategoryResponse res = service.saveCategory(req);

      assertThat(res.getName()).isEqualTo("Root");
      assertThat(res.getParentId()).isNull();
      verify(categoryRepository).save(mapped);
    }

    @Test
    void attachesToExistingParent() {
      Category parent = category("Electronics", null);
      CategoryRequest req = request("Laptops", parent.getId());
      Category mapped = new Category("Laptops", null);
      when(categoryMapper.toEntity(req)).thenReturn(mapped);
      when(categoryRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
      when(categoryRepository.save(mapped)).thenReturn(mapped);
      stubToResponse();

      CategoryResponse res = service.saveCategory(req);

      assertThat(mapped.getParent()).isSameAs(parent);
      assertThat(parent.getChildren()).contains(mapped);
      assertThat(res.getParentId()).isEqualTo(parent.getId());
    }

    @Test
    void failsWhenParentMissing() {
      UUID parentId = UUID.randomUUID();
      CategoryRequest req = request("Laptops", parentId);
      when(categoryMapper.toEntity(req)).thenReturn(new Category("Laptops", null));
      when(categoryRepository.findById(parentId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.saveCategory(req))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessageContaining("Parent category not found");
      verify(categoryRepository, never()).save(any());
    }
  }

  @Nested
  class UpdateCategory {
    @Test
    void throwsWhenCategoryMissing() {
      UUID id = UUID.randomUUID();
      when(categoryRepository.findById(id)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.updateCategory(id, request("x", null)))
          .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void trimsAndUpdatesName() {
      Category c = category("Old", null);
      when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));
      stubToResponse();

      CategoryResponse res = service.updateCategory(c.getId(), request("  New  ", null));

      assertThat(c.getName()).isEqualTo("New");
      assertThat(res.getName()).isEqualTo("New");
    }

    @Test
    void nullNameLeavesNameUnchanged() {
      Category c = category("Keep", null);
      when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));
      stubToResponse();

      service.updateCategory(c.getId(), request(null, null));

      assertThat(c.getName()).isEqualTo("Keep");
    }

    @Test
    void sameParentIsANoOpForHierarchy() {
      Category parent = category("P", null);
      Category c = category("C", parent);
      when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));
      stubToResponse();

      service.updateCategory(c.getId(), request(null, parent.getId()));

      assertThat(c.getParent()).isSameAs(parent);
      assertThat(parent.getChildren()).containsExactly(c);
      verify(categoryRepository, never()).findById(parent.getId());
    }

    @Test
    void rejectsSelfAsParent() {
      Category c = category("C", null);
      when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));

      assertThatThrownBy(() -> service.updateCategory(c.getId(), request(null, c.getId())))
          .isInstanceOf(AppException.class)
          .hasMessage("A category cannot be its own parent");
    }

    @Test
    void rejectsMovingUnderOwnDescendant() {
      Category root = category("Root", null);
      Category child = category("Child", root);
      Category grandchild = category("Grandchild", child);
      when(categoryRepository.findById(root.getId())).thenReturn(Optional.of(root));
      when(categoryRepository.findById(grandchild.getId())).thenReturn(Optional.of(grandchild));

      assertThatThrownBy(
              () -> service.updateCategory(root.getId(), request(null, grandchild.getId())))
          .isInstanceOf(AppException.class)
          .hasMessage("Cannot move a category under one of its own descendants");
      assertThat(root.getParent()).isNull();
    }

    @Test
    void failsWhenNewParentMissing() {
      Category c = category("C", null);
      UUID missing = UUID.randomUUID();
      when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));
      when(categoryRepository.findById(missing)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.updateCategory(c.getId(), request(null, missing)))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessageContaining("Parent category not found");
    }

    @Test
    void movesBetweenParentsAndUpdatesBothChildLists() {
      Category oldParent = category("Old", null);
      Category newParent = category("New", null);
      Category c = category("C", oldParent);
      when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));
      when(categoryRepository.findById(newParent.getId())).thenReturn(Optional.of(newParent));
      stubToResponse();

      CategoryResponse res = service.updateCategory(c.getId(), request(null, newParent.getId()));

      assertThat(c.getParent()).isSameAs(newParent);
      assertThat(oldParent.getChildren()).doesNotContain(c);
      assertThat(newParent.getChildren()).containsExactly(c);
      assertThat(res.getParentId()).isEqualTo(newParent.getId());
    }

    @Test
    void nullParentIdDetachesToRoot() {
      Category parent = category("P", null);
      Category c = category("C", parent);
      when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));
      stubToResponse();

      service.updateCategory(c.getId(), request(null, null));

      assertThat(c.getParent()).isNull();
      assertThat(parent.getChildren()).isEmpty();
    }

    @Test
    void rootCategoryCanBeAttachedToParent() {
      Category c = category("C", null);
      Category parent = category("P", null);
      when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));
      when(categoryRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
      stubToResponse();

      service.updateCategory(c.getId(), request(null, parent.getId()));

      assertThat(c.getParent()).isSameAs(parent);
      assertThat(parent.getChildren()).containsExactly(c);
    }
  }

  @Nested
  class DeleteCategory {
    @Test
    void deletesExistingCategory() {
      Category c = category("C", null);
      when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));

      service.deleteCategory(c.getId());

      verify(categoryRepository).delete(c);
    }

    @Test
    void throwsWhenMissing() {
      UUID id = UUID.randomUUID();
      when(categoryRepository.findById(id)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.deleteCategory(id))
          .isInstanceOf(EntityNotFoundException.class);
      verify(categoryRepository, never()).delete(any(Category.class));
    }
  }
}
