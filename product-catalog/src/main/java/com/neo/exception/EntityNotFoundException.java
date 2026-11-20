package com.neo.exception;

import org.springframework.http.HttpStatus;

public class EntityNotFoundException extends AppException {

  private final Class<?> entityType;
  private final Object id;

  public EntityNotFoundException(Class<?> entityType, Object id) {
    super(
        HttpStatus.NOT_FOUND,
        "ENTITY_NOT_FOUND",
        "%s not found with id: %s".formatted(entityType.getSimpleName(), id));
    this.entityType = entityType;
    this.id = id;
  }

  public Class<?> getEntityType() {
    return entityType;
  }

  public Object getId() {
    return id;
  }
}
