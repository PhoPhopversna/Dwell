package com.neo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neo.dto.ProductImageResponse;
import com.neo.entity.ProductImage;
import com.neo.service.ProductImageService;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductImageController.class)
class ProductImageControllerTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private ProductImageService service;

  private final UUID productId = UUID.randomUUID();
  private final String base = "/api/v1/products/" + productId + "/images";

  private static ProductImageResponse response(int position) {
    ProductImageResponse r = new ProductImageResponse();
    r.setId(UUID.randomUUID());
    r.setUrl("http://minio/images/a.png");
    r.setPosition(position);
    r.setStatus(ProductImage.ImageStatus.PROCESSING);
    return r;
  }

  private static MockMultipartFile file(String name) {
    return new MockMultipartFile("images", name, "image/png", new byte[] {1});
  }

  @Test
  void uploadAddsEachFileAndReturns201() throws Exception {
    when(service.addImageToProduct(eq(productId), any()))
        .thenReturn(response(0))
        .thenReturn(response(1));

    mvc.perform(multipart(base).file(file("a.png")).file(file("b.png")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[1].position").value(1))
        .andExpect(jsonPath("$.data[0].status").value("PROCESSING"));

    verify(service, times(2)).addImageToProduct(eq(productId), any());
  }

  @Test
  void uploadWithoutFilesReturns400() throws Exception {
    mvc.perform(multipart(base)).andExpect(status().isBadRequest());

    verifyNoInteractions(service);
  }

  @Test
  void getAllReturnsGallery() throws Exception {
    when(service.getImages(productId)).thenReturn(List.of(response(0), response(1)));

    mvc.perform(get(base))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));
  }

  @Test
  void patchMovesImage() throws Exception {
    UUID imageId = UUID.randomUUID();
    when(service.updatePosition(productId, imageId, 2)).thenReturn(response(2));

    mvc.perform(
            patch(base + "/" + imageId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"position\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.position").value(2));
  }

  @Test
  void patchRejectsNegativeOrMissingPosition() throws Exception {
    UUID imageId = UUID.randomUUID();

    mvc.perform(
            patch(base + "/" + imageId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"position\":-1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(patch(base + "/" + imageId).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(service);
  }

  @Test
  void deleteDelegatesToService() throws Exception {
    UUID imageId = UUID.randomUUID();

    mvc.perform(delete(base + "/" + imageId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("Delete Successfully"));

    verify(service).deleteImage(productId, imageId);
  }

  @Test
  void deleteImageNotOnProductReturns404() throws Exception {
    UUID imageId = UUID.randomUUID();
    doThrow(new EntityNotFoundException("Image not found"))
        .when(service)
        .deleteImage(productId, imageId);

    mvc.perform(delete(base + "/" + imageId)).andExpect(status().isNotFound());
  }
}
