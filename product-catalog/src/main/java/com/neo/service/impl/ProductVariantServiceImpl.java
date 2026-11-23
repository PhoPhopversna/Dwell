package com.neo.service.impl;

import com.neo.dto.ProductVariantRequest;
import com.neo.dto.ProductVariantResponse;
import com.neo.entity.Product;
import com.neo.entity.ProductVariant;
import com.neo.exception.AppException;
import com.neo.mapper.ProductMapper;
import com.neo.repository.ProductRepository;
import com.neo.repository.ProductVariantRepository;
import com.neo.service.ProductVariantService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProductVariantServiceImpl implements ProductVariantService {

  private final ProductVariantRepository productVariantRepository;
  private final ProductRepository productRepository;
  private final ProductMapper productMapper;

  public ProductVariantServiceImpl(
      ProductVariantRepository productVariantRepository,
      ProductRepository productRepository,
      ProductMapper productMapper) {
    this.productVariantRepository = productVariantRepository;
    this.productRepository = productRepository;
    this.productMapper = productMapper;
  }

  @Override
  @Transactional
  public ProductVariantResponse create(UUID productId, ProductVariantRequest request) {
    Product product =
        productRepository
            .findById(productId)
            .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
    if (productVariantRepository.existsBySku(request.getSku())) {
      throw duplicateSku(request.getSku());
    }

    ProductVariant variant = new ProductVariant();
    apply(variant, request);
    product.addVariant(variant);
    return productMapper.toResponse(productVariantRepository.save(variant));
  }

  @Override
  @Transactional
  public List<ProductVariantResponse> getAll(UUID productId) {
    if (!productRepository.existsById(productId)) {
      throw new EntityNotFoundException("Product not found: " + productId);
    }
    return productVariantRepository.findByProductId(productId).stream()
        .map(productMapper::toResponse)
        .toList();
  }

  @Override
  @Transactional
  public ProductVariantResponse getById(UUID productId, UUID variantId) {
    return productMapper.toResponse(findVariant(productId, variantId));
  }

  @Override
  @Transactional
  public ProductVariantResponse update(
      UUID productId, UUID variantId, ProductVariantRequest request) {
    ProductVariant variant = findVariant(productId, variantId);
    if (!variant.getSku().equals(request.getSku())
        && productVariantRepository.existsBySku(request.getSku())) {
      throw duplicateSku(request.getSku());
    }

    apply(variant, request);
    return productMapper.toResponse(productVariantRepository.save(variant));
  }

  @Override
  @Transactional
  public void delete(UUID productId, UUID variantId) {
    ProductVariant variant = findVariant(productId, variantId);
    variant.getProduct().getVariants().remove(variant);
    productVariantRepository.delete(variant);
  }

  private ProductVariant findVariant(UUID productId, UUID variantId) {
    return productVariantRepository
        .findByIdAndProductId(variantId, productId)
        .orElseThrow(() -> new EntityNotFoundException("Variant not found: " + variantId));
  }

  private static void apply(ProductVariant variant, ProductVariantRequest request) {
    variant.setSku(request.getSku());
    variant.setPriceOverride(request.getPrice());
    variant.setVariantAttributes(
        request.getAttributes() != null ? new HashMap<>(request.getAttributes()) : new HashMap<>());
    variant.setBarcode(request.getBarcode());
  }

  private static AppException duplicateSku(String sku) {
    return new AppException(
        HttpStatus.CONFLICT, "VARIANT_SKU_EXISTS", "Variant SKU already exists: " + sku);
  }
}
