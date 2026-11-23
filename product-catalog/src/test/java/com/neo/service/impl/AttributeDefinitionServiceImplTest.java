package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neo.dto.AttributeDefinitionRequest;
import com.neo.dto.AttributeDefinitionResponse;
import com.neo.entity.AttributeDefinition;
import com.neo.entity.AttributeDefinition.DataType;
import com.neo.entity.Category;
import com.neo.exception.AppException;
import com.neo.mapper.AttributeDefinitionMapper;
import com.neo.mapper.AttributeDefinitionMapperImpl;
import com.neo.repository.AttributeDefinitionRepository;
import com.neo.repository.CategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AttributeDefinitionServiceImplTest {

  @Mock private AttributeDefinitionRepository attributeDefinitionRepository;
  @Mock private CategoryRepository categoryRepository;
  @Spy private AttributeDefinitionMapper mapper = new AttributeDefinitionMapperImpl();
  @InjectMocks private AttributeDefinitionServiceImpl service;

  private Category category;

  @BeforeEach
  void setUp() {
    category = new Category("Shirts", null);
    category.setId(UUID.randomUUID());
  }

  private static AttributeDefinitionRequest request(String key, DataType type, List<String> opts) {
    AttributeDefinitionRequest r = new AttributeDefinitionRequest();
    r.setKey(key);
    r.setLabel(key.toUpperCase());
    r.setDataType(type);
    r.setOptions(opts);
    return r;
  }

  private AttributeDefinition existing(String key) {
    AttributeDefinition d = new AttributeDefinition(category, key, key, DataType.TEXT);
    d.setId(UUID.randomUUID());
    category.getAttributeDefinitions().add(d);
    when(attributeDefinitionRepository.findByIdAndCategoryId(d.getId(), category.getId()))
        .thenReturn(Optional.of(d));
    return d;
  }

  private void saveReturnsArgument() {
    when(attributeDefinitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private static HttpStatus statusOf(Throwable t) {
    return ((AppException) t).getStatus();
  }

  @Test
  void createAttachesDefinitionToCategory() {
    when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
    saveReturnsArgument();
    AttributeDefinitionRequest req = request("size", DataType.SELECT, List.of("S", "M"));
    req.setRequired(true);

    AttributeDefinitionResponse res = service.create(category.getId(), req);

    assertThat(res.getKey()).isEqualTo("size");
    assertThat(res.getOptions()).containsExactly("S", "M");
    assertThat(res.isRequired()).isTrue();
    assertThat(category.getAttributeDefinitions())
        .singleElement()
        .satisfies(d -> assertThat(d.getCategory()).isSameAs(category));
  }

  @Test
  void createDropsOptionsForNonChoiceTypes() {
    when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
    saveReturnsArgument();

    AttributeDefinitionResponse res =
        service.create(category.getId(), request("note", DataType.TEXT, List.of("ignored")));

    assertThat(res.getOptions()).isNull();
  }

  @Test
  void createRejectsChoiceTypeWithoutOptions() {
    when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));

    assertThatThrownBy(
            () -> service.create(category.getId(), request("size", DataType.MULTISELECT, List.of())))
        .isInstanceOf(AppException.class)
        .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(attributeDefinitionRepository, never()).save(any());
  }

  @Test
  void createRejectsDuplicateKeyInCategory() {
    when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
    when(attributeDefinitionRepository.existsByCategoryIdAndKey(category.getId(), "color"))
        .thenReturn(true);

    assertThatThrownBy(
            () -> service.create(category.getId(), request("color", DataType.TEXT, null)))
        .isInstanceOf(AppException.class)
        .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void createInMissingCategoryThrows() {
    UUID id = UUID.randomUUID();
    when(categoryRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(id, request("x", DataType.TEXT, null)))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void getAllListsCategoryDefinitions() {
    AttributeDefinition d = new AttributeDefinition(category, "color", "Color", DataType.TEXT);
    when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
    when(attributeDefinitionRepository.findByCategoryId(category.getId())).thenReturn(List.of(d));

    assertThat(service.getAll(category.getId()))
        .extracting(AttributeDefinitionResponse::getKey)
        .containsExactly("color");
  }

  @Test
  void getByIdFromAnotherCategoryIsNotFound() {
    UUID id = UUID.randomUUID();
    when(attributeDefinitionRepository.findByIdAndCategoryId(id, category.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getById(category.getId(), id))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void updateReplacesAllFields() {
    AttributeDefinition d = existing("color");
    saveReturnsArgument();
    AttributeDefinitionRequest req = request("colour", DataType.SELECT, List.of("red"));
    req.setFilterable(true);

    AttributeDefinitionResponse res = service.update(category.getId(), d.getId(), req);

    assertThat(res.getKey()).isEqualTo("colour");
    assertThat(res.getLabel()).isEqualTo("COLOUR");
    assertThat(res.getDataType()).isEqualTo(DataType.SELECT);
    assertThat(res.getOptions()).containsExactly("red");
    assertThat(res.isFilterable()).isTrue();
  }

  @Test
  void updateKeepingSameKeySkipsDuplicateCheck() {
    AttributeDefinition d = existing("color");
    saveReturnsArgument();

    service.update(category.getId(), d.getId(), request("color", DataType.TEXT, null));

    verify(attributeDefinitionRepository, never()).existsByCategoryIdAndKey(any(), any());
  }

  @Test
  void updateToKeyUsedByAnotherDefinitionConflicts() {
    AttributeDefinition d = existing("color");
    when(attributeDefinitionRepository.existsByCategoryIdAndKey(category.getId(), "size"))
        .thenReturn(true);

    assertThatThrownBy(
            () -> service.update(category.getId(), d.getId(), request("size", DataType.TEXT, null)))
        .isInstanceOf(AppException.class)
        .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void deleteRemovesFromCategoryAndRepository() {
    AttributeDefinition d = existing("color");

    service.delete(category.getId(), d.getId());

    assertThat(category.getAttributeDefinitions()).isEmpty();
    verify(attributeDefinitionRepository).delete(d);
  }
}
