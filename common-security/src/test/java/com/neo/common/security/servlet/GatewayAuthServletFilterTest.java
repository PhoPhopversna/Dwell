package com.neo.common.security.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neo.common.security.GatewayAuthProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayAuthServletFilterTest {

  private static final String HEADER = "X-Gateway-Secret";

  private final GatewayAuthServletFilter filter =
      new GatewayAuthServletFilter(
          new GatewayAuthProperties(true, HEADER, "s3cret", List.of("/swagger-ui/**")));

  private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void allowsRequestWithGatewaySecret() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/categories");
    request.addHeader(HEADER, "s3cret");
    MockFilterChain chain = new MockFilterChain();
    assertThat(run(request, chain).getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void rejectsMissingOrWrongSecretWithJson() throws Exception {
    MockHttpServletRequest wrong = new MockHttpServletRequest("POST", "/api/categories");
    wrong.addHeader(HEADER, "guess");
    for (MockHttpServletRequest request :
        List.of(new MockHttpServletRequest("POST", "/api/categories"), wrong)) {
      MockFilterChain chain = new MockFilterChain();
      MockHttpServletResponse response = run(request, chain);
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getContentAsString()).contains("\"success\":false", "Access denied");
      assertThat(chain.getRequest()).isNull();
    }
  }

  @Test
  void allowsExcludedPath() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    run(new MockHttpServletRequest("GET", "/swagger-ui/index.html"), chain);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void failsFastWithoutSecret() {
    assertThatThrownBy(
            () ->
                new GatewayAuthServletFilter(
                    new GatewayAuthProperties(true, HEADER, " ", List.of())))
        .isInstanceOf(IllegalStateException.class);
  }
}
