package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neo.entity.Product;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

@Data
public class ProductResponse {

  private UUID id;
  private String sku;
  private String name;

  @JsonProperty("category_id")
  private UUID categoryId;

  @JsonProperty("category_name")
  private String categoryName;

  @JsonProperty("base_price")
  private BigDecimal basePrice;

  @JsonProperty("cost_price")
  private BigDecimal costPrice;

  private Product.Status status;
  private Map<String, Object> attributes;
  private List<ProductVariantResponse> variants;

  @JsonProperty("created_at")
  private Instant createdAt;

  @JsonProperty("updated_at")
  private Instant updatedAt;
}
