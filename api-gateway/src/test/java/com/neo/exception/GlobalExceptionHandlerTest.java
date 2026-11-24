package com.neo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.neo.dto.GenericResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ServerWebInputException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void notFoundKeepsItsStatusAndMessage() {
    ResponseEntity<GenericResponse<Object>> r =
        handler
            .handleAppException(
                new RouteNotFoundException(
                    HttpStatus.NOT_FOUND, "404", "Route with id 9 is not found"))
            .block();
    assertThat(r.getStatusCode().value()).isEqualTo(404);
    assertThat(r.getBody().getMessage()).isEqualTo("Route with id 9 is not found");
    assertThat(r.getBody().isSuccess()).isFalse();
  }

  @Test
  void badInputIs400WithAGeneralMessage() {
    var r =
        handler
            .handleBadInput(
                new ServerWebInputException(
                    "Failed to read HTTP message",
                    null,
                    new DecodingException("JSON decoding error: Unexpected character ('b')")))
            .block();
    assertThat(r.getStatusCode().value()).isEqualTo(400);
    assertThat(r.getBody().getMessage()).isEqualTo("Invalid request");
  }

  @Test
  void frameworkStatusExceptionsKeepTheirStatus() {
    var r =
        handler
            .handleResponseStatus(
                new MethodNotAllowedException(
                    "PUT", java.util.List.of(org.springframework.http.HttpMethod.GET)))
            .block();
    assertThat(r.getStatusCode().value()).isEqualTo(405);
    assertThat(r.getBody().getMessage()).isEqualTo("Method Not Allowed");
  }

  @Test
  void unexpectedErrorsAre500AndNeverRevealDetails() {
    var r =
        handler
            .handleUnexpected(new IllegalStateException("r2dbc pool db-host:5432 exhausted"))
            .block();
    assertThat(r.getStatusCode().value()).isEqualTo(500);
    assertThat(r.getBody().getMessage()).isEqualTo("An unexpected error occurred");

    var five =
        handler
            .handleAppException(
                new AppException(HttpStatus.SERVICE_UNAVAILABLE, "503", "redis down at host:6379"))
            .block();
    assertThat(five.getBody().getMessage()).doesNotContain("redis");
  }
}
