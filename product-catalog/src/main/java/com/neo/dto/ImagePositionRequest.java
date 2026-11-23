package com.neo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Moves an image to a new place in the product's gallery (0 = first). */
@Data
public class ImagePositionRequest {
  @NotNull
  @Min(0)
  private Integer position;
}
