package com.neo.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

@Data
public class ProductVariantRequest {

  @NotBlank
  @Size(max = 64)
  private String sku;

  /** Price override for this variant. Null means "use the product's base price". */
  @DecimalMin(value = "0.0", inclusive = true)
  @Digits(integer = 10, fraction = 2)
  private BigDecimal price;

  /** What distinguishes this variant, e.g. {"size":"M","color":"red"}. */
  private Map<String, Object> attributes;

  @Size(max = 64)
  private String barcode;
}
