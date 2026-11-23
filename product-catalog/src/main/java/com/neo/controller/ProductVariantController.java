package com.neo.controller;

import com.neo.dto.CustomApiResponse;
import com.neo.dto.ProductVariantRequest;
import com.neo.dto.ProductVariantResponse;
import com.neo.service.ProductVariantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products/{productId}/variants")
@Tag(name = "Product variants", description = "Variants (size, color, ...) of a product")
public class ProductVariantController {

  private final ProductVariantService productVariantService;

  public ProductVariantController(ProductVariantService productVariantService) {
    this.productVariantService = productVariantService;
  }

  @Operation(summary = "Add a variant to a product")
  @PostMapping
  public ResponseEntity<CustomApiResponse<ProductVariantResponse>> create(
      @PathVariable UUID productId, @Valid @RequestBody ProductVariantRequest request) {
    ProductVariantResponse created = productVariantService.create(productId, request);
    URI location = URI.create("/api/v1/products/" + productId + "/variants/" + created.getId());
    return ResponseEntity.created(location).body(CustomApiResponse.ok(created));
  }

  @Operation(summary = "List the variants of a product")
  @GetMapping
  public ResponseEntity<CustomApiResponse<List<ProductVariantResponse>>> getAll(
      @PathVariable UUID productId) {
    return ResponseEntity.ok(CustomApiResponse.ok(productVariantService.getAll(productId)));
  }

  @Operation(summary = "Get one variant")
  @GetMapping("/{variantId}")
  public ResponseEntity<CustomApiResponse<ProductVariantResponse>> getById(
      @PathVariable UUID productId, @PathVariable UUID variantId) {
    return ResponseEntity.ok(
        CustomApiResponse.ok(productVariantService.getById(productId, variantId)));
  }

  @Operation(summary = "Replace a variant")
  @PutMapping("/{variantId}")
  public ResponseEntity<CustomApiResponse<ProductVariantResponse>> update(
      @PathVariable UUID productId,
      @PathVariable UUID variantId,
      @Valid @RequestBody ProductVariantRequest request) {
    return ResponseEntity.ok(
        CustomApiResponse.ok(productVariantService.update(productId, variantId, request)));
  }

  @Operation(summary = "Delete a variant")
  @DeleteMapping("/{variantId}")
  public ResponseEntity<CustomApiResponse<String>> delete(
      @PathVariable UUID productId, @PathVariable UUID variantId) {
    productVariantService.delete(productId, variantId);
    return ResponseEntity.ok(CustomApiResponse.ok("Delete Successfully"));
  }
}
