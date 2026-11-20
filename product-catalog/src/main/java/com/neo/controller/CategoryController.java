package com.neo.controller;

import com.neo.dto.CategoryRequest;
import com.neo.dto.CategoryResponse;
import com.neo.service.CategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
@Tag(name = "Products", description = "Product management")
public class CategoryController {

  private final CategoryService categoryService;

  public CategoryController(CategoryService categoryService) {
    this.categoryService = categoryService;
  }

  @GetMapping("/{id}")
  public ResponseEntity<CategoryResponse> getCategoryById(@PathVariable UUID id) {
      CategoryResponse category = categoryService.getCategoryById(id);
    return ResponseEntity.ok(category);
  }

  @GetMapping()
  public ResponseEntity<List<CategoryResponse>> getCategories() {
      List<CategoryResponse> categories = categoryService.getCategories();
      return ResponseEntity.ok(categories);
  }

  @PostMapping()
  public void postCategory(@RequestBody CategoryRequest categoryRequest) {
    categoryService.saveCategory(categoryRequest);
  }

  @PatchMapping("/{id}")
    public ResponseEntity<CategoryResponse> patchCategory(@PathVariable UUID id, @RequestBody CategoryRequest categoryRequest){
      CategoryResponse category = categoryService.updateCategory(id, categoryRequest);
      return ResponseEntity.ok(category);
  }

  @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteCategory(@PathVariable UUID id){
      categoryService.deleteCategory(id);
      return ResponseEntity.ok("Delete Successfully");
  }

}
