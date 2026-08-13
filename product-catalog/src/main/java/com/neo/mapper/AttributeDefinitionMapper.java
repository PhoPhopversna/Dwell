package com.neo.mapper;


import com.neo.dto.AttributeDefinitionRequest;
import com.neo.dto.AttributeDefinitionResponse;
import com.neo.entity.AttributeDefinition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

@Mapper(componentModel = "spring")
public interface AttributeDefinitionMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "dataType", source = "dataType")
    AttributeDefinition toEntity(AttributeDefinitionRequest request);

    AttributeDefinitionResponse toResponse(AttributeDefinition entity);
}
