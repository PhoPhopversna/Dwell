package com.neo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

import com.neo.dto.AttributeDefinitionRequest;
import com.neo.dto.AttributeDefinitionResponse;
import com.neo.entity.AttributeDefinition.DataType;
import com.neo.exception.AppException;
import com.neo.service.AttributeDefinitionService;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AttributeDefinitionController.class)
class AttributeDefinitionControllerTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private AttributeDefinitionService service;

  private final UUID categoryId = UUID.randomUUID();
  private final String base = "/api/categories/" + categoryId + "/attributes";
  private static final String BODY =
      "{\"key\":\"size\",\"label\":\"Size\",\"data_type\":\"SELECT\",\"options\":[\"S\",\"M\"]}";

  private static AttributeDefinitionResponse response(String key) {
    AttributeDefinitionResponse r = new AttributeDefinitionResponse();
    r.setId(UUID.randomUUID());
    r.setKey(key);
    r.setDataType(DataType.SELECT);
    return r;
  }

  @Test
  void createReturns201WithLocation() throws Exception {
    AttributeDefinitionResponse created = response("size");
    when(service.create(eq(categoryId), any(AttributeDefinitionRequest.class))).thenReturn(created);

    mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", base + "/" + created.getId()))
        .andExpect(jsonPath("$.data.key").value("size"))
        .andExpect(jsonPath("$.data.data_type").value("SELECT"));
  }

  @Test
  void createRejectsMissingRequiredFields() throws Exception {
    mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"x\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(service);
  }

  @Test
  void createDuplicateKeyReturns409() throws Exception {
    when(service.create(eq(categoryId), any()))
        .thenThrow(new AppException(HttpStatus.CONFLICT, "ATTRIBUTE_KEY_EXISTS", "exists"));

    mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("exists"));
  }

  @Test
  void getAllReturnsList() throws Exception {
    when(service.getAll(categoryId)).thenReturn(List.of(response("a"), response("b")));

    mvc.perform(get(base))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));
  }

  @Test
  void getByIdNotFoundReturns404() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.getById(categoryId, id)).thenThrow(new EntityNotFoundException("missing"));

    mvc.perform(get(base + "/" + id)).andExpect(status().isNotFound());
  }

  @Test
  void updateReturnsUpdatedDefinition() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.update(eq(categoryId), eq(id), any())).thenReturn(response("size"));

    mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.key").value("size"));
  }

  @Test
  void deleteDelegatesToService() throws Exception {
    UUID id = UUID.randomUUID();

    mvc.perform(delete(base + "/" + id)).andExpect(status().isOk());

    verify(service).delete(categoryId, id);
  }

  @Test
  void deleteNotFoundReturns404() throws Exception {
    UUID id = UUID.randomUUID();
    doThrow(new EntityNotFoundException("missing")).when(service).delete(categoryId, id);

    mvc.perform(delete(base + "/" + id)).andExpect(status().isNotFound());
  }
}
