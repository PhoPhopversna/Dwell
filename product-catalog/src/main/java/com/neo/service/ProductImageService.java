package com.neo.service;

import com.neo.dto.ProductImageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface ProductImageService {

  /** Uploads the file to MinIO, appends it to the gallery and queues thumbnail generation. */
  ProductImageResponse addImageToProduct(UUID productId, MultipartFile file) throws Exception;

  List<ProductImageResponse> getImages(UUID productId);

  /** Moves an image within the gallery; the other images shift to keep positions 0..n-1. */
  ProductImageResponse updatePosition(UUID productId, UUID imageId, int position);

  void deleteImage(UUID productId, UUID imageId);
}
