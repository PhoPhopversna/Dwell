package com.neo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayAuthFilterTest {

  private static final String HEADER = "X-Gateway-Secret";
  private static final String SECRET = "s3cret";

  private final GatewayAuthFilter filter =
      new GatewayAuthFilter(
          new GatewayAuthProperties(true, HEADER, SECRET, List.of("/v3/api-docs/**")),
          new ObjectMapper());

  private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void allowsRequestWithGatewaySecret() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
    request.addHeader(HEADER, SECRET);
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response = run(request, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void rejectsDirectRequestWithoutHeader() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response =
        run(new MockHttpServletRequest("GET", "/api/products"), chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("\"success\":false");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void rejectsRequestWithWrongSecret() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
    request.addHeader(HEADER, "wrong");
    MockFilterChain chain = new MockFilterChain();

    assertThat(run(request, chain).getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void allowsExcludedPathWithoutHeader() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response =
        run(new MockHttpServletRequest("GET", "/v3/api-docs/swagger-config"), chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void failsFastWhenSecretMissing() {
    assertThatThrownBy(
            () ->
                new GatewayAuthFilter(
                    new GatewayAuthProperties(true, HEADER, " ", List.of()), new ObjectMapper()))
        .isInstanceOf(IllegalStateException.class);
  }
}
