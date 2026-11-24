package com.neo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neo.dto.CategoryRequest;
import com.neo.dto.CategoryResponse;
import com.neo.exception.AppException;
import com.neo.service.CategoryService;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CategoryController.class)
class CategoryControllerTest {

  private static final String BASE = "/api/categories";

  @Autowired private MockMvc mvc;
  @MockitoBean private CategoryService categoryService;

  private static CategoryResponse response(String name, UUID parentId) {
    CategoryResponse r = new CategoryResponse();
    r.setId(UUID.randomUUID());
    r.setName(name);
    r.setParentId(parentId);
    return r;
  }

  @Test
  void getByIdReturnsCategory() throws Exception {
    UUID parent = UUID.randomUUID();
    CategoryResponse res = response("Laptops", parent);
    when(categoryService.getCategoryById(res.getId())).thenReturn(res);

    mvc.perform(get(BASE + "/" + res.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(res.getId().toString()))
        .andExpect(jsonPath("$.data.name").value("Laptops"))
        .andExpect(jsonPath("$.data.parent_id").value(parent.toString()));
  }

  @Test
  void getByIdRejectsMalformedUuid() throws Exception {
    mvc.perform(get(BASE + "/123")).andExpect(status().isBadRequest());
  }

  @Test
  void listReturnsAllCategories() throws Exception {
    when(categoryService.getCategories())
        .thenReturn(List.of(response("A", null), response("B", null)));

    mvc.perform(get(BASE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].name").value("A"))
        .andExpect(jsonPath("$.data[1].name").value("B"));
  }

  @Test
  void postDeserializesRequestAndReturns201() throws Exception {
    UUID parent = UUID.randomUUID();
    CategoryResponse created = response("Laptops", parent);
    when(categoryService.saveCategory(any(CategoryRequest.class))).thenReturn(created);
    String body =
        """
        {"parent_id":"%s","name":"Laptops",
         "attributeDefinitionRequestList":[{"key":"color","label":"Color","data_type":"TEXT"}]}
        """
            .formatted(parent);

    mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", BASE + "/" + created.getId()))
        .andExpect(jsonPath("$.data.id").value(created.getId().toString()));

    ArgumentCaptor<CategoryRequest> captor = ArgumentCaptor.forClass(CategoryRequest.class);
    verify(categoryService).saveCategory(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Laptops");
    assertThat(captor.getValue().getParentId()).isEqualTo(parent);
    assertThat(captor.getValue().getAttributeDefinitionRequestList())
        .singleElement()
        .satisfies(a -> assertThat(a.getKey()).isEqualTo("color"));
  }

  @Test
  void patchReturnsUpdatedCategory() throws Exception {
    UUID id = UUID.randomUUID();
    CategoryResponse res = response("Renamed", null);
    when(categoryService.updateCategory(eq(id), any(CategoryRequest.class))).thenReturn(res);

    mvc.perform(
            patch(BASE + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Renamed"));
  }

  @Test
  void deleteReturnsConfirmationText() throws Exception {
    UUID id = UUID.randomUUID();

    mvc.perform(delete(BASE + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("Delete Successfully"));

    verify(categoryService).deleteCategory(id);
  }

  @Test
  void getByIdReturns404WhenMissing() throws Exception {
    UUID id = UUID.randomUUID();
    when(categoryService.getCategoryById(id))
        .thenThrow(new EntityNotFoundException("Not Found :" + id));

    mvc.perform(get(BASE + "/" + id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  void patchReturns400ForInvalidHierarchyMove() throws Exception {
    UUID id = UUID.randomUUID();
    when(categoryService.updateCategory(eq(id), any(CategoryRequest.class)))
        .thenThrow(
            new AppException(
                HttpStatus.BAD_REQUEST,
                "CATEGORY_OWN_PARENT",
                "A category cannot be its own parent"));

    mvc.perform(
            patch(BASE + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parent_id\":\"" + id + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("A category cannot be its own parent"));
  }

  @Test
  void deleteReturns409WhenCategoryStillInUse() throws Exception {
    UUID id = UUID.randomUUID();
    doThrow(new DataIntegrityViolationException("fk")).when(categoryService).deleteCategory(id);

    mvc.perform(delete(BASE + "/" + id)).andExpect(status().isConflict());
  }
}
