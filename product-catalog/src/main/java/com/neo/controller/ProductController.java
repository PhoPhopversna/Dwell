package com.neo.controller;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
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
  public ResponseEntity<ProductResponse> create(
      @Valid @RequestPart("product") ProductRequest product,
      @RequestPart(value = "images", required = false) List<MultipartFile> images)
      throws Exception {

    Product created = productService.createProduct(product, images);
    URI location = URI.create("/api/v1/products/" + created.getId());
    return ResponseEntity.created(location).body(productMapper.toResponse(created));
  }
}
