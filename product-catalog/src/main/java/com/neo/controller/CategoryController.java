package com.neo.controller;


import com.neo.dto.CategoryRequest;
import com.neo.dto.CategoryResponse;
import com.neo.entity.Category;
import com.neo.service.CategoryService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/category")
public class CategoryController {

    private CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/{id}")
    public CategoryResponse getCategory(@PathVariable UUID id){
        return categoryService.getCategory(id);
    }

    @PostMapping()
    public void postCategory(@RequestBody  CategoryRequest categoryRequest){
        categoryService.saveCategory(categoryRequest);
    }
}
