package com.neo.service.impl;

import com.neo.dto.CategoryRequest;
import com.neo.dto.CategoryResponse;
import com.neo.entity.Category;
import com.neo.exception.AppException;
import com.neo.mapper.CategoryMapper;
import com.neo.repository.CategoryRepository;
import com.neo.service.CategoryService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CategoryServiceImpl implements CategoryService {
  private final CategoryRepository categoryRepository;
  private final CategoryMapper categoryMapper;

  public CategoryServiceImpl(CategoryRepository categoryRepository, CategoryMapper categoryMapper) {
    this.categoryRepository = categoryRepository;
    this.categoryMapper = categoryMapper;
  }

  @Override
  public List<CategoryResponse> getCategories() {

    List<CategoryResponse> categoryResponses =
        categoryRepository.findAll().stream()
            .map(category -> categoryMapper.toResponse(category))
            .collect(Collectors.toList());
    return categoryResponses;
  }

  @Override
  public CategoryResponse getCategoryById(UUID id) {
    Category category =
        categoryRepository
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Category not found: " + id));
    return categoryMapper.toResponse(category);
  }

  @Override
  @Transactional
  public CategoryResponse saveCategory(CategoryRequest categoryRequest) {
    Category category = categoryMapper.toEntity(categoryRequest);

    UUID parentId = categoryRequest.getParentId();
    if (parentId != null) {
      Category parent =
          categoryRepository
              .findById(parentId)
              .orElseThrow(
                  () -> new EntityNotFoundException("Parent category not found: " + parentId));
      category.setParent(parent);
      parent.getChildren().add(category);
    }

    Category saved = categoryRepository.save(category);
    return categoryMapper.toResponse(saved);
  }

  @Override
  @Transactional
  public CategoryResponse updateCategory(UUID id, CategoryRequest categoryRequest) {
    Category category =
        categoryRepository
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Category not found: " + id));

    if (categoryRequest.getName() != null) {
      category.setName(categoryRequest.getName().trim());
    }

    UUID newParentId = categoryRequest.getParentId();
    UUID currentParentId = category.getParent() != null ? category.getParent().getId() : null;

    if (!Objects.equals(newParentId, currentParentId)) {
      Category newParent = null;

      if (newParentId != null) {
        if (newParentId.equals(id)) {
          throw new AppException(
              HttpStatus.BAD_REQUEST, "CATEGORY_OWN_PARENT", "A category cannot be its own parent");
        }

        newParent =
            categoryRepository
                .findById(newParentId)
                .orElseThrow(
                    () -> new EntityNotFoundException("Parent category not found: " + newParentId));

        for (Category ancestor = newParent; ancestor != null; ancestor = ancestor.getParent()) {
          if (ancestor.getId().equals(id)) {
            throw new AppException(
                HttpStatus.BAD_REQUEST,
                "CATEGORY_CYCLE",
                "Cannot move a category under one of its own descendants");
          }
        }
      }
      if (category.getParent() != null) {
        category.getParent().getChildren().remove(category);
      }
      category.setParent(newParent);
      if (newParent != null) {
        newParent.getChildren().add(category);
      }
    }

    return categoryMapper.toResponse(category);
  }

  @Override
  public void deleteCategory(UUID id) {
    Category category =
        categoryRepository
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Category not found: " + id));
    categoryRepository.delete(category);
  }
}
