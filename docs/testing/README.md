# Testing guide

This guide explains how the tests are built and how each test works: what it sets up, what it does, and what it checks. Use it to understand what is covered, to find the right test when you change something, and to write new tests the same way.

| Module | Tests | Per-test reference |
|---|---|---|
| common-logging | 57 | [common-logging.md](common-logging.md) |
| api-gateway | 23 | [api-gateway.md](api-gateway.md) |
| identity | 40 | [identity.md](identity.md) |
| product-catalog | 157 | [product-catalog.md](product-catalog.md) |
| **Total** | **277** | |

## Running the tests

```bash
mvn test                                         # everything
mvn -pl identity test                            # one module
mvn -pl identity test -Dtest=UserServiceImplTest # one class
mvn -pl identity test -Dtest='UserServiceImplTest#databaseDownIs503'   # one test
```

What has to be running:

| Tests | Needs |
|---|---|
| common-logging, api-gateway, identity | Nothing: no database, Keycloak, Redis or Kafka |
| product-catalog repository tests (`*RepositoryTest`) | **Docker.** Testcontainers starts a throwaway `postgres:16-alpine` |
| product-catalog `ProductCatalogApplicationTests` | **Local PostgreSQL** on 5432 with the `product-catalog` database (it boots the whole app with the real config) |

## Test types and how they work

Every pattern below is used in the code; copy an existing test when writing a new one.

### 1. Plain unit test with Mockito

**Used for** service classes: `*ServiceImplTest`, `ImageProcessingConsumerTest`.

The class under test gets mocks for everything it depends on (repositories, MinIO, Kafka, other services). Each test stubs what the mocks return, calls one method, then checks the result and which calls were or weren't made.

```java
@ExtendWith(MockitoExtension.class)
class ProductVariantServiceImplTest {
  @Mock ProductVariantRepository productVariantRepository;   // fake
  @InjectMocks ProductVariantServiceImpl service;            // real class, fakes injected

  @Test
  void createRejectsDuplicateSku() {
    when(productVariantRepository.existsBySku("TS-1-M")).thenReturn(true);   // given
    assertThatThrownBy(() -> service.create(productId, request("TS-1-M", null)))  // when
        .isInstanceOf(AppException.class);                                  // then
    verify(productVariantRepository, never()).save(any());                 // and nothing saved
  }
}
```

Tools used:
- `ArgumentCaptor` checks what was passed to a mock.
- `inOrder(...)` checks call order, e.g. "delete the row, *then* publish the Kafka event".
- `verifyNoInteractions(...)` proves something was **not** touched, e.g. MinIO is never called for an invalid file.
- `@Spy` with a real MapStruct mapper (`AttributeDefinitionServiceImplTest`) runs the real mapping instead of stubbing it.

**Transactions:** services that use `TransactionTemplate` get a mock that runs the callback straight away:

```java
when(transactionTemplate.execute(any()))
    .thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(mock(TransactionStatus.class)));
```

### 2. Reactive code (WebFlux)

identity and the gateway return `Mono`/`Flux`. Tests either call `.block()` and assert on the result (identity has no `reactor-test`), or use `StepVerifier` (gateway):

```java
assertThatThrownBy(() -> service.syncAuthenticatedUser(claims, "LOGIN", "c-1").block())
    .isInstanceOf(AuthException.class);

StepVerifier.create(service.getRoute(9L)).expectError(DataBaseException.class).verify();
```

To run code as a logged-in admin, put a JWT in the Reactor context:

```java
Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(adminSub).claim("x", "y").build();
call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt, List.of())));
```

### 3. Web filters (WebFlux): `MockServerWebExchange`

**Used for** `CorrelationIdWebFilterTest`, `RouteAuthorizationFilterTest`, `GatewayAuthWebFilterTest`, `DownstreamErrorFilterTest`.

The test builds a fake request and calls the filter directly, passing a small lambda as "the rest of the chain". The lambda records whether the request got through and can write a response, throw, or switch threads like real I/O. The test then reads the response status, headers and body.

```java
AtomicBoolean forwarded = new AtomicBoolean();
GatewayFilterChain chain = e -> { forwarded.set(true); return Mono.empty(); };
filter.filter(exchange, chain).block();
assertThat(forwarded).isFalse();
assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
```

### 4. Servlet filters: `MockHttpServletRequest` / `MockFilterChain`

**Used for** `CorrelationIdServletFilterTest` and `GatewayAuthFilterTest` (product-catalog). The same idea with the servlet mocks: `chain.getRequest()` is null if the filter stopped the request.

### 5. Controller slice: `@WebMvcTest` + `MockMvc`

**Used for** the product-catalog `*ControllerTest` classes.

Spring starts only the web layer: one controller, JSON conversion, validation and the `GlobalExceptionHandler`. The service is a `@MockitoBean`. The test sends a real HTTP request and checks the status, headers and JSON (`jsonPath`). This proves request parsing, `@Valid` rules, status codes and the response wrapper, without any business logic or database.

```java
mvc.perform(post(base).contentType(APPLICATION_JSON).content("{\"sku\":\"\",\"price\":-1}"))
   .andExpect(status().isBadRequest());
verifyNoInteractions(service);                    // validation stopped it before the service
```

Multipart uploads use `multipart(BASE).file(new MockMultipartFile("images", "a.png", "image/png", bytes))`.

### 6. Repository slice: `@DataJpaTest` on real PostgreSQL

**Used for** the product-catalog `*RepositoryTest` classes. They all extend `AbstractRepositoryTest`, which starts **one** PostgreSQL container shared by every repository test (`@ServiceConnection` wires it in). It must be real PostgreSQL, because the entities use `jsonb` columns that H2 can't handle.

The usual pattern:
1. Save data with `TestEntityManager`.
2. `em.flush(); em.clear();` so the next read really goes to the database.
3. Read it back and check.

This proves mappings, cascades, unique constraints and custom queries.

### 7. Spring wiring: `ApplicationContextRunner`

**Used for** `CorrelationLoggingAutoConfigurationTest` and `SchedulingConfigTest`.

The test starts a tiny Spring context with only the configuration being tested, plus Spring Boot's own auto-configurations. That proves the wiring really works, e.g. that `@Async` runs on Boot's executor and keeps the correlation id, or that `@Scheduled` is really registered every 60 seconds.

### 8. Faking HTTP calls

**Faked `WebClient`:** the test passes a lambda that answers every request, with no network (`KeycloakGenericConnectorTest`, `KeycloakAuthServiceImplTest`):
```java
WebClient.builder().exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST).build())).build();
```

**`MockRestServiceServer`** for `RestTemplate` and `RestClient`: it states which request and headers are expected and replies (`CorrelationLoggingAutoConfigurationTest`).

### 9. Checking log output: `CapturedLogs`

`common-logging/src/test/.../CapturedLogs.java` attaches an in-memory appender to the root logger. Tests read the captured events:
- `logs.singleAccessCompleted()` returns the one access line and fails if there are zero or two;
- `value(event, "status")` reads a structured field;
- `correlationId(event)` reads the MDC.

That's how the tests prove "exactly one access line per request" and "every line carries the correlation id".

### 10. Kafka

There's no Kafka broker in the tests:
- **Interceptors** (`KafkaCorrelationIdTest`) are called directly with a `ProducerRecord` / `ConsumerRecord`.
- **Listeners** (`ImageProcessingConsumerTest`) are called as plain methods with mocked MinIO and repository, and a `ConsumerRecord` built by hand for the DLT handler.

The retry-topic flow itself (`KafkaRetryConfig`) was checked against a real broker by hand. See [Not covered](#not-covered).

## Conventions

- **Test names are sentences** that state the rule: `failedLocalSaveDeletesTheKeycloakUser`, `routeWithoutRolesIsDenied`. If one fails, the name tells you which rule broke.
- **One rule per test.** Related variants of the same rule share a test, e.g. three kinds of bad file in `nonImageOrEmptyFileIsRejectedBeforeAnythingIsStored`.
- **Always check what must *not* happen,** not just the result: nothing saved, nothing published, MinIO not called, secret not in the message.
- **Security and privacy rules are tested explicitly:** error bodies never reveal roles, Keycloak details, SQL or class names, and logs never contain passwords or tokens.
- **Regression tests for fixed bugs** say so in their doc: `missingRouteIs404_notADatabaseError`, `emptyBody4xxIsAnError`.
- Format with `mvn -pl <module> spotless:apply`.

## Not covered

These have no direct tests. Changes there need extra care, or a test first.

| Area | Class | What isn't tested |
|---|---|---|
| Attribute validation | `ProductAttributeValidator` | The type rules (TEXT, NUMBER, BOOLEAN, DATE, SELECT, MULTISELECT), required and unknown keys. Only mocked in `ProductServiceImplTest` |
| Roles | `RoleServiceImpl` | Every role endpoint's Keycloak calls |
| Keycloak admin token | `KeycloakAdminServiceImpl` | Redis cache, refresh, fallback to a new login |
| First admin | `AdminBootstrap` | Role and user creation, retries |
| identity HTTP layer | `AuthController`, `RoleController`, `UserController` | No `@WebFluxTest` slice tests |
| Gateway routing | `RouteForwardServiceImpl` | Building routes from the table, method-specific routes before catch-all |
| Rate limiting | `RateLimitServiceImpl`, `RateLimitFilter`, `rate_limiter.lua` | The limit itself and the 429 response (only the scheduling is tested) |
| Gateway secret | `GatewaySecretHeaderFilter` | That the header is added and overwrites a client value |
| Gateway route API | `RouteController` | HTTP layer |
| Kafka | `KafkaPublisher`, `KafkaRetryConfig` | Failure logging; retry topics and DLT (checked by hand on a real broker) |
| Storage | `MinioBucketInitializer` | Bucket creation and anonymous-read policy |
| Across services | – | No automated end-to-end tests; the full flow is checked with the Postman collection (`postman/`, not in git) |
