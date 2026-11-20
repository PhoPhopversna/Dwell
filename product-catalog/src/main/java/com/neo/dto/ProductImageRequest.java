package com.neo.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ProductImageRequest {

  @NotBlank private String url;

  @Min(0)
  private int position;
}
