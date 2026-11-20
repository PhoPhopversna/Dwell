package com.neo.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

@Data
public class ProductVariantRequest {

  @NotBlank
  @Size(max = 64)
  private String sku; // ASSUMPTION — adjust to match real ProductVariant fields

  @NotNull
  @DecimalMin(value = "0.0", inclusive = true)
  private BigDecimal price;

  private Map<String, Object> attributes; // e.g. size/color overrides
}
