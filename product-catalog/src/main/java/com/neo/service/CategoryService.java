package com.neo.service;

import com.neo.dto.CategoryRequest;
import com.neo.dto.CategoryResponse;
import com.neo.entity.Category;
import com.neo.mapper.CategoryMapper;
import com.neo.repository.CategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class CategoryService {

    CategoryRepository categoryRepository;
    CategoryMapper categoryMapper;


    public CategoryService(CategoryRepository categoryRepository, CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
    }

    public CategoryResponse getCategory(UUID uuid){
        Category category = categoryRepository.findById(uuid).orElseThrow(() -> new EntityNotFoundException("Not Found :" + uuid));
        return categoryMapper.toResponse(category);
    }

    public Category saveCategory(CategoryRequest categoryRequest){
        Category category = categoryMapper.toEntity(categoryRequest);
        return categoryRepository.save(category);
    }
}
