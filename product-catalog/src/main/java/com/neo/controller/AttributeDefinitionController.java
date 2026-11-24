package com.neo.controller;

import com.neo.dto.AttributeDefinitionRequest;
import com.neo.dto.AttributeDefinitionResponse;
import com.neo.dto.CustomApiResponse;
import com.neo.service.AttributeDefinitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories/{categoryId}/attributes")
@Tag(name = "Category attributes", description = "Attribute definitions of a category")
public class AttributeDefinitionController {

  private final AttributeDefinitionService attributeDefinitionService;

  public AttributeDefinitionController(AttributeDefinitionService attributeDefinitionService) {
    this.attributeDefinitionService = attributeDefinitionService;
  }

  @Operation(summary = "Add an attribute definition to a category")
  @PostMapping
  public ResponseEntity<CustomApiResponse<AttributeDefinitionResponse>> create(
      @PathVariable UUID categoryId, @Valid @RequestBody AttributeDefinitionRequest request) {
    AttributeDefinitionResponse created = attributeDefinitionService.create(categoryId, request);
    URI location = URI.create("/api/categories/" + categoryId + "/attributes/" + created.getId());
    return ResponseEntity.created(location).body(CustomApiResponse.ok(created));
  }

  @Operation(summary = "List the attribute definitions of a category")
  @GetMapping
  public ResponseEntity<CustomApiResponse<List<AttributeDefinitionResponse>>> getAll(
      @PathVariable UUID categoryId) {
    return ResponseEntity.ok(CustomApiResponse.ok(attributeDefinitionService.getAll(categoryId)));
  }

  @Operation(summary = "Get one attribute definition")
  @GetMapping("/{id}")
  public ResponseEntity<CustomApiResponse<AttributeDefinitionResponse>> getById(
      @PathVariable UUID categoryId, @PathVariable UUID id) {
    return ResponseEntity.ok(
        CustomApiResponse.ok(attributeDefinitionService.getById(categoryId, id)));
  }

  @Operation(summary = "Replace an attribute definition")
  @PutMapping("/{id}")
  public ResponseEntity<CustomApiResponse<AttributeDefinitionResponse>> update(
      @PathVariable UUID categoryId,
      @PathVariable UUID id,
      @Valid @RequestBody AttributeDefinitionRequest request) {
    return ResponseEntity.ok(
        CustomApiResponse.ok(attributeDefinitionService.update(categoryId, id, request)));
  }

  @Operation(summary = "Delete an attribute definition")
  @DeleteMapping("/{id}")
  public ResponseEntity<CustomApiResponse<String>> delete(
      @PathVariable UUID categoryId, @PathVariable UUID id) {
    attributeDefinitionService.delete(categoryId, id);
    return ResponseEntity.ok(CustomApiResponse.ok("Delete Successfully"));
  }
}
