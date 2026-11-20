package com.neo.dto;

import java.util.UUID;
import lombok.Data;

@Data
public class ProductImageResponse {
  private UUID id;
  private String url;
  private int position;
}
