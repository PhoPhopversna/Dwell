package com.neo.service;

import com.neo.dto.AttributeDefinitionRequest;
import com.neo.dto.AttributeDefinitionResponse;
import java.util.List;
import java.util.UUID;

public interface AttributeDefinitionService {

  AttributeDefinitionResponse create(UUID categoryId, AttributeDefinitionRequest request);

  List<AttributeDefinitionResponse> getAll(UUID categoryId);

  AttributeDefinitionResponse getById(UUID categoryId, UUID id);

  AttributeDefinitionResponse update(UUID categoryId, UUID id, AttributeDefinitionRequest request);

  void delete(UUID categoryId, UUID id);
}
