package com.neo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.neo.dto.CustomApiResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private static String message(ResponseEntity<CustomApiResponse<Void>> r) {
    return r.getBody().getMessage();
  }

  @Test
  void ownNotFoundMessagesAreKept() {
    UUID id = UUID.randomUUID();
    var r =
        handler.handleNotFound(
            new jakarta.persistence.EntityNotFoundException("Product not found: " + id));
    assertThat(r.getStatusCode().value()).isEqualTo(404);
    assertThat(message(r)).isEqualTo("Product not found: " + id);
    assertThat(
            message(
                handler.handleNotFound(
                    new jakarta.persistence.EntityNotFoundException("Image not found"))))
        .isEqualTo("Image not found");
  }

  @Test
  void jpaInternalNotFoundMessagesAreReplaced() {
    var r =
        handler.handleNotFound(
            new jakarta.persistence.EntityNotFoundException(
                "Unable to find com.neo.entity.Product with id 3fa85f64-5717-4562-b3fc-2c963f66afa6"));
    assertThat(r.getStatusCode().value()).isEqualTo(404);
    assertThat(message(r)).isEqualTo("Resource not found").doesNotContain("com.neo");
  }

  @Test
  void libraryIllegalArgumentMessagesAreReplaced() {
    var r =
        handler.handleIllegalArgument(
            new IllegalArgumentException("Invalid UUID string: x; at org.hibernate..."));
    assertThat(r.getStatusCode().value()).isEqualTo(400);
    assertThat(message(r)).isEqualTo("Invalid request");
  }

  @Test
  void ownBusinessRulesAreKept() {
    assertThat(
            message(
                handler.handleAppException(
                    new AppException(
                        HttpStatus.BAD_REQUEST,
                        "CATEGORY_CYCLE",
                        "Cannot move a category under one of its own descendants"))))
        .isEqualTo("Cannot move a category under one of its own descendants");
    assertThat(
            message(
                handler.handleAttributeValidation(
                    new AttributeValidationException(
                        List.of("Missing required attribute: color"), HttpStatus.BAD_REQUEST))))
        .contains("Missing required attribute: color");
  }

  @Test
  void databaseDetailsAreNeverReturned() {
    var r =
        handler.handleConflict(
            new DataIntegrityViolationException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint \"uk_products_sku\"]"));
    assertThat(r.getStatusCode().value()).isEqualTo(409);
    assertThat(message(r)).doesNotContain("uk_products_sku").doesNotContain("statement");
  }

  @Test
  void serverErrorsAreAlwaysGeneral() {
    var r =
        handler.handleAppException(
            new AppException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MINIO",
                "MinIO at http://minio:9000 bucket images: access denied"));
    assertThat(r.getStatusCode().value()).isEqualTo(500);
    assertThat(message(r)).isEqualTo("An unexpected error occurred");
  }

  @Test
  void validationErrorsListTheFieldsButNotTheRejectedValues() throws Exception {
    var target = new Object();
    var binding = new org.springframework.validation.BeanPropertyBindingResult(target, "request");
    binding.addError(
        new org.springframework.validation.FieldError(
            "request", "name", "s3cr3t-value", false, null, null, "must not be blank"));
    var param =
        new org.springframework.core.MethodParameter(
            Object.class.getMethod("equals", Object.class), 0);
    var r =
        handler.handleValidation(
            new org.springframework.web.bind.MethodArgumentNotValidException(param, binding));

    assertThat(r.getStatusCode().value()).isEqualTo(400);
    assertThat(message(r))
        .isEqualTo("Validation failed: name: must not be blank")
        .doesNotContain("s3cr3t");
    assertThat(r.getBody().isSuccess()).isFalse();
  }

  @Test
  void malformedJsonGetsAGeneralMessage() {
    var r =
        handler.handleUnreadable(
            new org.springframework.http.converter.HttpMessageNotReadableException(
                "JSON parse error: Unexpected character ('b')",
                new org.springframework.mock.http.MockHttpInputMessage(new byte[0])));
    assertThat(r.getStatusCode().value()).isEqualTo(400);
    assertThat(message(r)).isEqualTo("Malformed request body");
  }

  @Test
  void wrongParameterTypeNamesTheParameterOnly() {
    var r =
        handler.handleTypeMismatch(
            new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "abc", UUID.class, "id", null, new IllegalArgumentException("Invalid UUID")));
    assertThat(r.getStatusCode().value()).isEqualTo(400);
    assertThat(message(r)).isEqualTo("Invalid value for 'id'");
  }

  @Test
  void tooLargeUploadIs413() {
    var r =
        handler.handleTooLarge(
            new org.springframework.web.multipart.MaxUploadSizeExceededException(1024));
    assertThat(r.getStatusCode().value()).isEqualTo(413);
    assertThat(message(r)).isEqualTo("Uploaded file is too large");
  }

  @Test
  void springExceptionsKeepTheirStatus_unknownExceptionsAre500WithAGeneralMessage() {
    var notAllowed =
        handler.handleUnexpected(
            new org.springframework.web.HttpRequestMethodNotSupportedException("PUT"));
    assertThat(notAllowed.getStatusCode().value()).isEqualTo(405);
    assertThat(message(notAllowed)).isEqualTo("Method not allowed");

    var boom =
        handler.handleUnexpected(new IllegalStateException("Connection to db-host:5432 refused"));
    assertThat(boom.getStatusCode().value()).isEqualTo(500);
    assertThat(message(boom)).isEqualTo("An unexpected error occurred").doesNotContain("db-host");
  }

  @Test
  void storageFailureIs5xxWithAGeneralMessage() {
    var r =
        handler.handleAppException(
            new AppException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "IMAGE_STORAGE_UNAVAILABLE",
                "Failed to store image x in bucket images: Connection refused",
                new java.io.IOException("Connection refused")));
    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(message(r)).isEqualTo("An unexpected error occurred");
  }
}
