package com.neo.mapper;


import com.neo.dto.CategoryRequest;
import com.neo.dto.CategoryResponse;
import com.neo.entity.Category;
import com.neo.repository.CategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;


@Mapper(componentModel = "spring", uses = { AttributeDefinitionMapper.class, CategoryResolver.class })
public interface CategoryMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "children", ignore = true)
    @Mapping(target = "parent", source = "parentId")
    @Mapping(target = "attributeDefinitions", source = "attributeDefinitionRequestList")
    Category toEntity(CategoryRequest request);

    @Mapping(target = "parentId", source = "parent.id")
    CategoryResponse toResponse (Category category);

    @AfterMapping
    default void linkChildren(@MappingTarget Category category) {
        if (category.getAttributeDefinitions() != null) {
            category.getAttributeDefinitions().forEach(attr -> attr.setCategory(category));
        }
    }
}
