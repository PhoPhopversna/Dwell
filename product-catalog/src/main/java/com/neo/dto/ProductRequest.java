package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neo.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Product data sent in the `product` part of the multipart request")
public class ProductRequest {

  @Schema(description = "Unique stock keeping unit", example = "LP-14-2026")
  @NotBlank
  @Size(max = 64)
  private String sku;

  @Schema(description = "Product name", example = "Laptop Pro 14")
  @NotBlank
  @Size(max = 200)
  private String name;

  @Schema(
      description = "ID of an existing category",
      example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
  @JsonProperty("category_id")
  @NotNull
  private UUID categoryId;

  @Schema(description = "Selling price in USD (max 2 decimals)", example = "1299.99")
  @JsonProperty("base_price")
  @NotNull
  @DecimalMin(value = "0.0", inclusive = true)
  @Digits(integer = 10, fraction = 2)
  private BigDecimal basePrice;

  @Schema(description = "Internal cost price in USD (optional)", example = "950.00")
  @JsonProperty("cost_price")
  @DecimalMin(value = "0.0", inclusive = true)
  @Digits(integer = 10, fraction = 2)
  private BigDecimal costPrice;

  @Schema(
      description = "Product status. Defaults to the service's default if omitted.",
      example = "ACTIVE")
  private Product.Status status;

  @Schema(
      description = "Free-form product attributes as key/value pairs",
      example = "{\"color\": \"silver\", \"ram_gb\": 16, \"waterproof\": false}")
  @NotNull
  private Map<String, Object> attributes;

  @Schema(description = "Product variants (for example size or color combinations)")
  @Valid
  private List<ProductVariantRequest> variants;

  @Schema(
      description =
          "Metadata for the product images. The image files themselves are sent in the separate `images` part.")
  @Valid
  private List<ProductImageRequest> images;
}
