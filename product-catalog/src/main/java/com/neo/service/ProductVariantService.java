package com.neo.service;

import com.neo.dto.ProductVariantRequest;
import com.neo.dto.ProductVariantResponse;
import java.util.List;
import java.util.UUID;

public interface ProductVariantService {

  ProductVariantResponse create(UUID productId, ProductVariantRequest request);

  List<ProductVariantResponse> getAll(UUID productId);

  ProductVariantResponse getById(UUID productId, UUID variantId);

  ProductVariantResponse update(UUID productId, UUID variantId, ProductVariantRequest request);

  void delete(UUID productId, UUID variantId);
}
