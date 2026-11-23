package com.neo.service;

import com.neo.dto.ProductRequest;
import com.neo.entity.Product;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface ProductService {
  public Product createProduct(ProductRequest req, List<MultipartFile> images) throws Exception;

  public Product getProductById(UUID productId);

  public List<Product> getProducts();

  public Product updateProduct(UUID productId, ProductRequest req, List<MultipartFile> images)
      throws Exception;

  public void deleteProduct(UUID productId);
}
