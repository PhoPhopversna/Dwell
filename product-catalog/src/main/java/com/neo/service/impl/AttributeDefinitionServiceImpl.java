package com.neo.service.impl;

import com.neo.dto.AttributeDefinitionRequest;
import com.neo.dto.AttributeDefinitionResponse;
import com.neo.entity.AttributeDefinition;
import com.neo.entity.AttributeDefinition.DataType;
import com.neo.entity.Category;
import com.neo.exception.AppException;
import com.neo.mapper.AttributeDefinitionMapper;
import com.neo.repository.AttributeDefinitionRepository;
import com.neo.repository.CategoryRepository;
import com.neo.service.AttributeDefinitionService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AttributeDefinitionServiceImpl implements AttributeDefinitionService {

  private final AttributeDefinitionRepository attributeDefinitionRepository;
  private final CategoryRepository categoryRepository;
  private final AttributeDefinitionMapper mapper;

  public AttributeDefinitionServiceImpl(
      AttributeDefinitionRepository attributeDefinitionRepository,
      CategoryRepository categoryRepository,
      AttributeDefinitionMapper mapper) {
    this.attributeDefinitionRepository = attributeDefinitionRepository;
    this.categoryRepository = categoryRepository;
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public AttributeDefinitionResponse create(UUID categoryId, AttributeDefinitionRequest request) {
    Category category = findCategory(categoryId);
    validateOptions(request);
    if (attributeDefinitionRepository.existsByCategoryIdAndKey(categoryId, request.getKey())) {
      throw duplicateKey(request.getKey());
    }

    AttributeDefinition definition = mapper.toEntity(request);
    definition.setOptions(isChoiceType(request.getDataType()) ? request.getOptions() : null);
    definition.setCategory(category);
    category.getAttributeDefinitions().add(definition);
    return mapper.toResponse(attributeDefinitionRepository.save(definition));
  }

  @Override
  @Transactional
  public List<AttributeDefinitionResponse> getAll(UUID categoryId) {
    findCategory(categoryId);
    return attributeDefinitionRepository.findByCategoryId(categoryId).stream()
        .map(mapper::toResponse)
        .toList();
  }

  @Override
  @Transactional
  public AttributeDefinitionResponse getById(UUID categoryId, UUID id) {
    return mapper.toResponse(findDefinition(categoryId, id));
  }

  @Override
  @Transactional
  public AttributeDefinitionResponse update(
      UUID categoryId, UUID id, AttributeDefinitionRequest request) {
    AttributeDefinition definition = findDefinition(categoryId, id);
    validateOptions(request);
    if (!definition.getKey().equals(request.getKey())
        && attributeDefinitionRepository.existsByCategoryIdAndKey(categoryId, request.getKey())) {
      throw duplicateKey(request.getKey());
    }

    definition.setKey(request.getKey());
    definition.setLabel(request.getLabel());
    definition.setDataType(request.getDataType());
    definition.setOptions(isChoiceType(request.getDataType()) ? request.getOptions() : null);
    definition.setRequired(request.isRequired());
    definition.setFilterable(request.isFilterable());
    return mapper.toResponse(attributeDefinitionRepository.save(definition));
  }

  @Override
  @Transactional
  public void delete(UUID categoryId, UUID id) {
    AttributeDefinition definition = findDefinition(categoryId, id);
    definition.getCategory().getAttributeDefinitions().remove(definition);
    attributeDefinitionRepository.delete(definition);
  }

  private Category findCategory(UUID categoryId) {
    return categoryRepository
        .findById(categoryId)
        .orElseThrow(() -> new EntityNotFoundException("Category not found: " + categoryId));
  }

  private AttributeDefinition findDefinition(UUID categoryId, UUID id) {
    return attributeDefinitionRepository
        .findByIdAndCategoryId(id, categoryId)
        .orElseThrow(() -> new EntityNotFoundException("Attribute definition not found: " + id));
  }

  private static boolean isChoiceType(DataType type) {
    return type == DataType.SELECT || type == DataType.MULTISELECT;
  }

  /** SELECT/MULTISELECT need choices, otherwise product validation could never pass. */
  private static void validateOptions(AttributeDefinitionRequest request) {
    if (isChoiceType(request.getDataType())
        && (request.getOptions() == null || request.getOptions().isEmpty())) {
      throw new AppException(
          HttpStatus.BAD_REQUEST,
          "ATTRIBUTE_OPTIONS_REQUIRED",
          request.getDataType()
              + " attribute '"
              + request.getKey()
              + "' needs at least one option");
    }
  }

  private static AppException duplicateKey(String key) {
    return new AppException(
        HttpStatus.CONFLICT,
        "ATTRIBUTE_KEY_EXISTS",
        "Attribute '" + key + "' already exists in this category");
  }
}
