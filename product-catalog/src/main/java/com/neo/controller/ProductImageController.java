package com.neo.controller;

import com.neo.dto.CustomApiResponse;
import com.neo.dto.ImagePositionRequest;
import com.neo.dto.ProductImageResponse;
import com.neo.service.ProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/products/{productId}/images")
@Tag(name = "Product images", description = "Gallery images of a product")
public class ProductImageController {

  private final ProductImageService productImageService;

  public ProductImageController(ProductImageService productImageService) {
    this.productImageService = productImageService;
  }

  @Operation(
      summary = "Upload images to a product",
      description = "Multipart request with one or more `images` files, appended in order.")
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<CustomApiResponse<List<ProductImageResponse>>> upload(
      @PathVariable UUID productId, @RequestPart("images") List<MultipartFile> images)
      throws Exception {
    List<ProductImageResponse> uploaded = new ArrayList<>();
    for (MultipartFile image : images) {
      uploaded.add(productImageService.addImageToProduct(productId, image));
    }
    URI location = URI.create("/api/v1/products/" + productId + "/images");
    return ResponseEntity.created(location).body(CustomApiResponse.ok(uploaded));
  }

  @Operation(summary = "List a product's images in gallery order")
  @GetMapping
  public ResponseEntity<CustomApiResponse<List<ProductImageResponse>>> getAll(
      @PathVariable UUID productId) {
    return ResponseEntity.ok(CustomApiResponse.ok(productImageService.getImages(productId)));
  }

  @Operation(summary = "Move an image to a new gallery position")
  @PatchMapping("/{imageId}")
  public ResponseEntity<CustomApiResponse<ProductImageResponse>> updatePosition(
      @PathVariable UUID productId,
      @PathVariable UUID imageId,
      @Valid @RequestBody ImagePositionRequest request) {
    return ResponseEntity.ok(
        CustomApiResponse.ok(
            productImageService.updatePosition(productId, imageId, request.getPosition())));
  }

  @Operation(summary = "Delete an image (its files are removed from storage asynchronously)")
  @DeleteMapping("/{imageId}")
  public ResponseEntity<CustomApiResponse<String>> delete(
      @PathVariable UUID productId, @PathVariable UUID imageId) {
    productImageService.deleteImage(productId, imageId);
    return ResponseEntity.ok(CustomApiResponse.ok("Delete Successfully"));
  }
}
