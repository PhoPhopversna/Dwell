package com.neo.config;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neo.common.security.DwellPermissions;
import com.neo.common.security.KeycloakRoleConverter;
import com.neo.common.security.autoconfigure.DwellSecurityAutoConfiguration;
import com.neo.controller.CategoryController;
import com.neo.dto.CategoryResponse;
import com.neo.service.CategoryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The real filter chain: gateway secret first, then JWT and role rules. */
@WebMvcTest(CategoryController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(DwellSecurityAutoConfiguration.class)
@TestPropertySource(
    properties = {"app.gateway-auth.enabled=true", "app.gateway-auth.secret=test-secret"})
class SecurityConfigTest {

  private static final String BASE = "/api/categories/";

  @Autowired private MockMvc mvc;
  @MockitoBean private CategoryService categoryService;

  /** A user with realm role ADMIN (ignored: only permissions count) and these permissions. */
  private static JwtRequestPostProcessor user(String... permissions) {
    return jwt()
        .jwt(
            j ->
                j.claim("realm_access", Map.of("roles", List.of("ADMIN")))
                    .claim(
                        "resource_access",
                        Map.of(DwellPermissions.CLIENT, Map.of("roles", List.of(permissions)))))
        .authorities(new KeycloakRoleConverter(DwellPermissions.CLIENT));
  }

  private static MockHttpServletRequestBuilder viaGateway(MockHttpServletRequestBuilder request) {
    return request.header("X-Gateway-Secret", "test-secret");
  }

  @Test
  void validTokenWithoutTheGatewaySecretIsRejected() throws Exception {
    mvc.perform(get(BASE + UUID.randomUUID()).with(user("category:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void gatewaySecretWithoutATokenIsRejected() throws Exception {
    mvc.perform(viaGateway(get(BASE + UUID.randomUUID())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Authentication required"));
  }

  @Test
  void categoryReadAllowsReading() throws Exception {
    UUID id = UUID.randomUUID();
    when(categoryService.getCategoryById(id)).thenReturn(new CategoryResponse());
    mvc.perform(viaGateway(get(BASE + id)).with(user("category:read"))).andExpect(status().isOk());
  }

  @Test
  void categoryReadDoesNotAllowChanges() throws Exception {
    mvc.perform(viaGateway(delete(BASE + UUID.randomUUID())).with(user("category:read")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Access denied"));
  }

  @Test
  void categoryWriteAllowsChanges() throws Exception {
    mvc.perform(viaGateway(delete(BASE + UUID.randomUUID())).with(user("category:write")))
        .andExpect(status().isOk());
  }

  @Test
  void realmRoleAloneCannotRead() throws Exception {
    // realm role ADMIN but no permission
    mvc.perform(viaGateway(get(BASE + UUID.randomUUID())).with(user()))
        .andExpect(status().isForbidden());
  }

  @Test
  void productPermissionsDoNotOpenCategories() throws Exception {
    mvc.perform(
            viaGateway(delete(BASE + UUID.randomUUID()))
                .with(user("product:read", "product:write")))
        .andExpect(status().isForbidden());
  }
}
