package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neo.dto.ProductVariantRequest;
import com.neo.dto.ProductVariantResponse;
import com.neo.entity.Product;
import com.neo.entity.ProductVariant;
import com.neo.exception.AppException;
import com.neo.mapper.ProductMapper;
import com.neo.repository.ProductRepository;
import com.neo.repository.ProductVariantRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ProductVariantServiceImplTest {

  @Mock private ProductVariantRepository productVariantRepository;
  @Mock private ProductRepository productRepository;
  @Mock private ProductMapper productMapper;
  @InjectMocks private ProductVariantServiceImpl service;

  private Product product;

  @BeforeEach
  void setUp() {
    product = new Product("TS-1", "T-Shirt", null, new BigDecimal("20.00"));
    product.setId(UUID.randomUUID());
  }

  private static ProductVariantRequest request(String sku, String price) {
    ProductVariantRequest r = new ProductVariantRequest();
    r.setSku(sku);
    r.setPrice(price == null ? null : new BigDecimal(price));
    r.setAttributes(Map.of("size", "M"));
    r.setBarcode("123");
    return r;
  }

  private ProductVariant existing(String sku) {
    ProductVariant v = new ProductVariant(product, sku, Map.of());
    v.setId(UUID.randomUUID());
    product.getVariants().add(v);
    when(productVariantRepository.findByIdAndProductId(v.getId(), product.getId()))
        .thenReturn(Optional.of(v));
    return v;
  }

  @Test
  void createAddsVariantToProduct() {
    when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
    when(productVariantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    ProductVariantResponse mapped = new ProductVariantResponse();
    when(productMapper.toResponse(any(ProductVariant.class))).thenReturn(mapped);

    ProductVariantResponse res = service.create(product.getId(), request("TS-1-M", "25.00"));

    assertThat(res).isSameAs(mapped);
    ProductVariant saved = product.getVariants().get(0);
    assertThat(saved.getProduct()).isSameAs(product);
    assertThat(saved.getSku()).isEqualTo("TS-1-M");
    assertThat(saved.getPriceOverride()).isEqualByComparingTo("25.00");
    assertThat(saved.getVariantAttributes()).containsEntry("size", "M");
    assertThat(saved.getBarcode()).isEqualTo("123");
  }

  @Test
  void createWithoutPriceInheritsBasePrice() {
    when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
    when(productVariantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.create(product.getId(), request("TS-1-L", null));

    assertThat(product.getVariants().get(0).getEffectivePrice()).isEqualByComparingTo("20.00");
  }

  @Test
  void createRejectsDuplicateSku() {
    when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
    when(productVariantRepository.existsBySku("TS-1-M")).thenReturn(true);

    assertThatThrownBy(() -> service.create(product.getId(), request("TS-1-M", null)))
        .isInstanceOf(AppException.class)
        .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    verify(productVariantRepository, never()).save(any());
  }

  @Test
  void createOnMissingProductThrows() {
    UUID id = UUID.randomUUID();
    when(productRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(id, request("X", null)))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void getAllListsVariantsOfProduct() {
    ProductVariant v = new ProductVariant(product, "TS-1-M", Map.of());
    when(productRepository.existsById(product.getId())).thenReturn(true);
    when(productVariantRepository.findByProductId(product.getId())).thenReturn(List.of(v));
    ProductVariantResponse mapped = new ProductVariantResponse();
    when(productMapper.toResponse(v)).thenReturn(mapped);

    assertThat(service.getAll(product.getId())).containsExactly(mapped);
  }

  @Test
  void getAllOfMissingProductThrows() {
    UUID id = UUID.randomUUID();
    when(productRepository.existsById(id)).thenReturn(false);

    assertThatThrownBy(() -> service.getAll(id)).isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void getByIdOfVariantOnAnotherProductIsNotFound() {
    UUID variantId = UUID.randomUUID();
    when(productVariantRepository.findByIdAndProductId(variantId, product.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getById(product.getId(), variantId))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining("Variant not found");
  }

  @Test
  void updateReplacesFields() {
    ProductVariant v = existing("TS-1-M");
    when(productVariantRepository.existsBySku("TS-1-XL")).thenReturn(false);
    when(productVariantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.update(product.getId(), v.getId(), request("TS-1-XL", "30.00"));

    ArgumentCaptor<ProductVariant> saved = ArgumentCaptor.forClass(ProductVariant.class);
    verify(productVariantRepository).save(saved.capture());
    assertThat(saved.getValue().getSku()).isEqualTo("TS-1-XL");
    assertThat(saved.getValue().getPriceOverride()).isEqualByComparingTo("30.00");
  }

  @Test
  void updateKeepingSkuSkipsDuplicateCheck() {
    ProductVariant v = existing("TS-1-M");
    when(productVariantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.update(product.getId(), v.getId(), request("TS-1-M", null));

    verify(productVariantRepository, never()).existsBySku(anyString());
  }

  @Test
  void updateToTakenSkuConflicts() {
    ProductVariant v = existing("TS-1-M");
    when(productVariantRepository.existsBySku("TAKEN")).thenReturn(true);

    assertThatThrownBy(() -> service.update(product.getId(), v.getId(), request("TAKEN", null)))
        .isInstanceOf(AppException.class);
    verify(productVariantRepository, never()).save(any());
  }

  @Test
  void deleteRemovesFromProductAndRepository() {
    ProductVariant v = existing("TS-1-M");

    service.delete(product.getId(), v.getId());

    assertThat(product.getVariants()).isEmpty();
    verify(productVariantRepository).delete(v);
  }
}
