package com.neo.controller;

import com.neo.dto.CategoryRequest;
import com.neo.dto.CategoryResponse;
import com.neo.dto.CustomApiResponse;
import com.neo.service.CategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
@Tag(name = "Categories", description = "Category management")
public class CategoryController {

  private final CategoryService categoryService;

  public CategoryController(CategoryService categoryService) {
    this.categoryService = categoryService;
  }

  @GetMapping("/{id}")
  public ResponseEntity<CustomApiResponse<CategoryResponse>> getCategoryById(
      @PathVariable UUID id) {
    CategoryResponse category = categoryService.getCategoryById(id);
    return ResponseEntity.ok(CustomApiResponse.ok(category));
  }

  @GetMapping()
  public ResponseEntity<CustomApiResponse<List<CategoryResponse>>> getCategories() {
    List<CategoryResponse> categories = categoryService.getCategories();
    return ResponseEntity.ok(CustomApiResponse.ok(categories));
  }

  @PostMapping()
  public ResponseEntity<CustomApiResponse<CategoryResponse>> postCategory(
      @RequestBody CategoryRequest categoryRequest) {
    CategoryResponse created = categoryService.saveCategory(categoryRequest);
    return ResponseEntity.created(URI.create("/api/categories/" + created.getId()))
        .body(CustomApiResponse.ok(created));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<CustomApiResponse<CategoryResponse>> patchCategory(
      @PathVariable UUID id, @RequestBody CategoryRequest categoryRequest) {
    CategoryResponse category = categoryService.updateCategory(id, categoryRequest);
    return ResponseEntity.ok(CustomApiResponse.ok(category));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<CustomApiResponse<String>> deleteCategory(@PathVariable UUID id) {
    categoryService.deleteCategory(id);
    return ResponseEntity.ok(CustomApiResponse.ok("Delete Successfully"));
  }
}
