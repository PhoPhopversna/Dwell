package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neo.entity.ProductImage;
import java.util.UUID;
import lombok.Data;

@Data
public class ProductImageResponse {
  private UUID id;
  private String url;

  @JsonProperty("thumbnail_url")
  private String thumbnailUrl;

  private int position;
  private ProductImage.ImageStatus status;
}
