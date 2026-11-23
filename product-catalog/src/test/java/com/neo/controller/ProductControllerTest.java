package com.neo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neo.dto.ProductRequest;
import com.neo.dto.ProductResponse;
import com.neo.entity.Product;
import com.neo.exception.AttributeValidationException;
import com.neo.mapper.ProductMapper;
import com.neo.service.ProductService;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

  private static final String BASE = "/api/v1/products";

  @Autowired private MockMvc mvc;
  @MockitoBean private ProductService productService;
  @MockitoBean private ProductMapper productMapper;

  private final UUID categoryId = UUID.randomUUID();

  private MockMultipartFile productPart(String json) {
    return new MockMultipartFile("product", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes());
  }

  private static MockMultipartFile imagePart() {
    return new MockMultipartFile("images", "a.png", "image/png", new byte[] {1});
  }

  private String validJson() {
    return """
        {"sku":"LP-14","name":"Laptop","category_id":"%s","base_price":1299.99,
         "attributes":{"color":"silver"}}
        """
        .formatted(categoryId);
  }

  private Product savedProduct() {
    Product p = new Product("LP-14", "Laptop", null, new BigDecimal("1299.99"));
    p.setId(UUID.randomUUID());
    return p;
  }

  private ProductResponse responseFor(Product p) {
    ProductResponse r = new ProductResponse();
    r.setId(p.getId());
    r.setSku(p.getSku());
    r.setName(p.getName());
    r.setCategoryId(categoryId);
    r.setBasePrice(p.getBasePrice());
    return r;
  }

  @Test
  void createReturns201WithLocationAndWrappedBody() throws Exception {
    Product saved = savedProduct();
    when(productService.createProduct(any(ProductRequest.class), anyList())).thenReturn(saved);
    when(productMapper.toResponse(saved)).thenReturn(responseFor(saved));

    mvc.perform(multipart(BASE).file(productPart(validJson())).file(imagePart()))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", BASE + "/" + saved.getId()))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.id").value(saved.getId().toString()))
        .andExpect(jsonPath("$.data.sku").value("LP-14"))
        .andExpect(jsonPath("$.data.category_id").value(categoryId.toString()))
        .andExpect(jsonPath("$.data.base_price").value(1299.99));
  }

  @Test
  void createPassesUploadedImagesToService() throws Exception {
    Product saved = savedProduct();
    when(productService.createProduct(any(ProductRequest.class), anyList())).thenReturn(saved);
    when(productMapper.toResponse(saved)).thenReturn(responseFor(saved));
    MockMultipartFile img1 = new MockMultipartFile("images", "a.png", "image/png", new byte[] {1});
    MockMultipartFile img2 = new MockMultipartFile("images", "b.png", "image/png", new byte[] {2});

    mvc.perform(multipart(BASE).file(productPart(validJson())).file(img1).file(img2))
        .andExpect(status().isCreated());

    verify(productService)
        .createProduct(
            any(ProductRequest.class),
            org.mockito.ArgumentMatchers.argThat(
                (List<org.springframework.web.multipart.MultipartFile> l) -> l.size() == 2));
  }

  @Test
  void createRejectsInvalidProductWith400() throws Exception {
    String invalid = "{\"sku\":\"\",\"name\":\"\",\"base_price\":-1}";

    mvc.perform(multipart(BASE).file(productPart(invalid))).andExpect(status().isBadRequest());

    verifyNoInteractions(productService);
  }

  @Test
  void createRequiresImagesPart() throws Exception {
    mvc.perform(multipart(BASE).file(productPart(validJson()))).andExpect(status().isBadRequest());

    verifyNoInteractions(productService);
  }

  @Test
  void createRequiresProductPart() throws Exception {
    mvc.perform(multipart(BASE)).andExpect(status().isBadRequest());

    verifyNoInteractions(productService);
  }


  @Test
  void updateDelegatesToServiceAndReturnsWrappedBody() throws Exception {
    Product updated = savedProduct();
    when(productService.updateProduct(eq(updated.getId()), any(ProductRequest.class), anyList()))
        .thenReturn(updated);
    when(productMapper.toResponse(updated)).thenReturn(responseFor(updated));

    mvc.perform(
            multipart(HttpMethod.PATCH, BASE + "/" + updated.getId())
                .file(productPart(validJson()))
                .file(imagePart()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.id").value(updated.getId().toString()));
  }

  @Test
  void updateRejectsInvalidProductWith400() throws Exception {
    mvc.perform(
            multipart(HttpMethod.PATCH, BASE + "/" + UUID.randomUUID())
                .file(productPart("{\"sku\":\"x\"}")))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(productService);
  }


  @Test
  void deleteReturnsSuccessMessage() throws Exception {
    UUID id = UUID.randomUUID();

    mvc.perform(delete(BASE + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").value("Delete Successfully"));

    verify(productService).deleteProduct(id);
  }

  @Test
  void attributeValidationErrorIsReturnedAs400() throws Exception {
    when(productService.createProduct(any(), any()))
        .thenThrow(new AttributeValidationException(List.of("bad"), HttpStatus.BAD_REQUEST));

    mvc.perform(multipart(BASE).file(productPart(validJson())).file(imagePart()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Attribute validation failed: bad"));
  }

  @Test
  void duplicateSkuIsReturnedAs409() throws Exception {
    when(productService.createProduct(any(), any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    mvc.perform(multipart(BASE).file(productPart(validJson())).file(imagePart()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  void getByIdReturnsWrappedProduct() throws Exception {
    Product p = savedProduct();
    when(productService.getProductById(p.getId())).thenReturn(p);
    when(productMapper.toResponse(p)).thenReturn(responseFor(p));

    mvc.perform(get(BASE + "/" + p.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.id").value(p.getId().toString()))
        .andExpect(jsonPath("$.data.sku").value("LP-14"));
  }

  @Test
  void getByIdReturns404WhenMissing() throws Exception {
    UUID id = UUID.randomUUID();
    when(productService.getProductById(id))
        .thenThrow(new EntityNotFoundException("Product not found: " + id));

    mvc.perform(get(BASE + "/" + id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Product not found: " + id));
  }

  @Test
  void getAllReturnsWrappedList() throws Exception {
    Product a = savedProduct();
    Product b = savedProduct();
    when(productService.getProducts()).thenReturn(List.of(a, b));
    when(productMapper.toResponse(any(Product.class)))
        .thenAnswer(inv -> responseFor(inv.getArgument(0)));

    mvc.perform(get(BASE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].id").value(a.getId().toString()))
        .andExpect(jsonPath("$.data[1].id").value(b.getId().toString()));
  }

  @Test
  void deleteReturns404WhenMissing() throws Exception {
    UUID id = UUID.randomUUID();
    doThrow(new EntityNotFoundException("Product not found: " + id))
        .when(productService)
        .deleteProduct(id);

    mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNotFound());
  }
}
