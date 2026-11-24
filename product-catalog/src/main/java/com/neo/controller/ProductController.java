package com.neo.controller;

import com.neo.dto.CustomApiResponse;
import com.neo.dto.ProductRequest;
import com.neo.dto.ProductResponse;
import com.neo.entity.Product;
import com.neo.mapper.ProductMapper;
import com.neo.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Slf4j
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Create, read, update and delete products")
public class ProductController {

  private final ProductService productService;
  private final ProductMapper productMapper;

  public ProductController(ProductService productService, ProductMapper productMapper) {
    this.productService = productService;
    this.productMapper = productMapper;
  }

  @Operation(
      summary = "Create a product",
      description =
          "Multipart request. Send `product` as a JSON part (content type application/json) "
              + "and optionally one or more `images` files. The SKU must be unique.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Product created"),
    @ApiResponse(
        responseCode = "400",
        description = "Validation failed",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "SKU already exists",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  })
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<CustomApiResponse<ProductResponse>> create(
      @Valid @RequestPart("product") ProductRequest product,
      @RequestPart(value = "images", required = true) List<MultipartFile> images)
      throws Exception {

    Product created = productService.createProduct(product, images);
    log.debug("Created product {}", created.getId());
    URI location = URI.create("/api/v1/products/" + created.getId());
    return ResponseEntity.created(location)
        .body(CustomApiResponse.ok(productMapper.toResponse(created)));
  }

  @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<CustomApiResponse<ProductResponse>> update(
      @PathVariable UUID id,
      @Valid @RequestPart("product") ProductRequest product,
      @RequestPart(value = "images", required = true) List<MultipartFile> images)
      throws Exception {

    Product updateProduct = productService.updateProduct(id, product, images);
    return ResponseEntity.ok(CustomApiResponse.ok(productMapper.toResponse(updateProduct)));
  }

  @Operation(summary = "Get a product by id")
  @GetMapping("/{id}")
  public ResponseEntity<CustomApiResponse<ProductResponse>> getById(@PathVariable UUID id) {
    return ResponseEntity.ok(
        CustomApiResponse.ok(productMapper.toResponse(productService.getProductById(id))));
  }

  @Operation(summary = "List all products")
  @GetMapping
  public ResponseEntity<CustomApiResponse<List<ProductResponse>>> getAll() {
    List<ProductResponse> products =
        productService.getProducts().stream().map(productMapper::toResponse).toList();
    return ResponseEntity.ok(CustomApiResponse.ok(products));
  }

  @Operation(summary = "Delete a product and all of its images")
  @DeleteMapping("/{id}")
  public ResponseEntity<CustomApiResponse<String>> delete(@PathVariable UUID id) {
    productService.deleteProduct(id);
    return ResponseEntity.ok(CustomApiResponse.ok("Delete Successfully"));
  }
}
