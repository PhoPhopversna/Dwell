package com.neo.service;

import com.neo.dto.ProductRequest;
import com.neo.entity.Product;
import com.neo.entity.ProductImage;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface ProductService {
  public Product createProduct(ProductRequest req, List<MultipartFile> images) throws Exception;

  public ProductImage addImageToProduct(UUID productId, MultipartFile file) throws Exception;
}
