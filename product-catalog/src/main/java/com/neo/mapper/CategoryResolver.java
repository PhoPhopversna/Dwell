package com.neo.mapper;

import com.neo.entity.Category;
import com.neo.repository.CategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CategoryResolver {
    private final CategoryRepository categoryRepository;
    CategoryResolver(CategoryRepository repo) { this.categoryRepository = repo; }

    public Category resolve(UUID id) {
        return id == null ? null : categoryRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Not Found category id :" + id ));
    }
}
