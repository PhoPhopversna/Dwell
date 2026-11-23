package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

@Data
public class ProductVariantResponse {
  private UUID id;
  private String sku;

  /** The variant's own price override; null when it inherits the product's base price. */
  private BigDecimal price;

  /** The price actually charged: the override, or the product's base price. */
  @JsonProperty("effective_price")
  private BigDecimal effectivePrice;

  private Map<String, Object> attributes;
  private String barcode;
}
