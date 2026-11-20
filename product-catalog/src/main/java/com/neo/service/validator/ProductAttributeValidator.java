package com.neo.service.validator;

import com.neo.entity.AttributeDefinition;
import com.neo.entity.Category;
import com.neo.exception.AttributeValidationException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ProductAttributeValidator {
  public void validate(Category category, Map<String, Object> attributes) {

    List<String> errors = new ArrayList<>();
    List<AttributeDefinition> attriDefs = category.getAttributeDefinitions();

    Set<String> knownKeys = new HashSet<>();
    if (attributes == null) {
      attributes = Collections.emptyMap();
    }
    for (AttributeDefinition def : attriDefs) {
      knownKeys.add(def.getKey());
      Object value = attributes.get(def.getKey());
      if (value == null) {
        if (def.isRequired()) {
          errors.add("Missing required attribute: " + def.getKey());
        }
        continue;
      }
      validateType(def, value, errors);
    }
    for (String key : attributes.keySet()) {
      if (!knownKeys.contains(key)) {
        errors.add("Unknown attribute for this category: " + key);
      }
    }

    if (!errors.isEmpty()) {
      throw new AttributeValidationException(errors, HttpStatus.BAD_REQUEST);
    }
  }

  private void validateType(AttributeDefinition def, Object value, List<String> errors) {
    String key = def.getKey();

    switch (def.getDataType()) {
      case TEXT -> {
        if (!(value instanceof String)) {
          errors.add(key + " must be a text value");
        }
      }
      case NUMBER -> {
        if (!(value instanceof Number)) {
          errors.add(key + " must be a number");
        }
      }
      case BOOLEAN -> {
        if (!(value instanceof Boolean)) {
          errors.add(key + " must be true/false");
        }
      }
      case DATE -> {
        if (!(value instanceof String s) || !isValidDate(s)) {
          errors.add(key + " must be a valid date (yyyy-MM-dd)");
        }
      }
      case SELECT -> {
        if (!(value instanceof String s) || !def.getOptions().contains(s)) {
          errors.add(key + " must be one of " + def.getOptions());
        }
      }
      case MULTISELECT -> {
        if (!(value instanceof List<?> list)) {
          errors.add(key + " must be a list of values");
        } else {
          List<?> invalid = list.stream().filter(v -> !def.getOptions().contains(v)).toList();
          if (!invalid.isEmpty()) {
            errors.add(
                key
                    + " contains invalid values "
                    + invalid
                    + " — must be one of "
                    + def.getOptions());
          }
        }
      }
    }
  }

  private boolean isValidDate(String s) {
    try {
      LocalDate.parse(s);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
