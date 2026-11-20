package com.neo.service;

import com.neo.dto.CategoryRequest;
import com.neo.dto.CategoryResponse;
import java.util.List;
import java.util.UUID;

public interface CategoryService {

  public List<CategoryResponse> getCategories();

  public CategoryResponse getCategoryById(UUID id);

  public CategoryResponse saveCategory(CategoryRequest categoryRequest);

  public CategoryResponse updateCategory(UUID id, CategoryRequest categoryRequest);

  public void deleteCategory(UUID id);
}
