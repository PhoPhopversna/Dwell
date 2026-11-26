package com.neo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neo.dto.ProductVariantRequest;
import com.neo.dto.ProductVariantResponse;
import com.neo.exception.AppException;
import com.neo.service.ProductVariantService;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductVariantController.class)
// Controller logic only; the security rules are tested in SecurityConfigTest
@AutoConfigureMockMvc(addFilters = false)
class ProductVariantControllerTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private ProductVariantService service;

  private final UUID productId = UUID.randomUUID();
  private final String base = "/api/v1/products/" + productId + "/variants";
  private static final String BODY =
      "{\"sku\":\"TS-1-M\",\"price\":25.00,\"attributes\":{\"size\":\"M\"}}";

  private static ProductVariantResponse response() {
    ProductVariantResponse r = new ProductVariantResponse();
    r.setId(UUID.randomUUID());
    r.setSku("TS-1-M");
    r.setPrice(new BigDecimal("25.00"));
    r.setEffectivePrice(new BigDecimal("25.00"));
    return r;
  }

  @Test
  void createReturns201WithLocation() throws Exception {
    ProductVariantResponse created = response();
    when(service.create(eq(productId), any(ProductVariantRequest.class))).thenReturn(created);

    mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", base + "/" + created.getId()))
        .andExpect(jsonPath("$.data.sku").value("TS-1-M"))
        .andExpect(jsonPath("$.data.effective_price").value(25.00));
  }

  @Test
  void createRejectsBlankSkuAndNegativePrice() throws Exception {
    mvc.perform(
            post(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"\",\"price\":-1}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(service);
  }

  @Test
  void createDuplicateSkuReturns409() throws Exception {
    when(service.create(eq(productId), any()))
        .thenThrow(new AppException(HttpStatus.CONFLICT, "VARIANT_SKU_EXISTS", "taken"));

    mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isConflict());
  }

  @Test
  void getAllReturnsList() throws Exception {
    when(service.getAll(productId)).thenReturn(List.of(response()));

    mvc.perform(get(base))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
  }

  @Test
  void getAllOfMissingProductReturns404() throws Exception {
    when(service.getAll(productId)).thenThrow(new EntityNotFoundException("missing"));

    mvc.perform(get(base)).andExpect(status().isNotFound());
  }

  @Test
  void updateReturnsUpdatedVariant() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.update(eq(productId), eq(id), any())).thenReturn(response());

    mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sku").value("TS-1-M"));
  }

  @Test
  void deleteDelegatesToService() throws Exception {
    UUID id = UUID.randomUUID();

    mvc.perform(delete(base + "/" + id)).andExpect(status().isOk());

    verify(service).delete(productId, id);
  }
}
