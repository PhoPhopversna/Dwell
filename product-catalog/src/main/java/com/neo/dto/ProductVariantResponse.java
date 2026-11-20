package com.neo.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

@Data
public class ProductVariantResponse {
  private UUID id;
  private String sku;
  private BigDecimal price;
  private Map<String, Object> attributes;
}
