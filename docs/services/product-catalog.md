# product-catalog

The catalog (port **8084**): categories with their own attribute definitions, products whose attributes are validated against their category, variants (sizes, colours, ...) and product images. Images are stored in MinIO; thumbnails are made in the background through Kafka, and the frontend is told over WebSocket when they're ready.

- **Stack:** Spring MVC, Spring Data JPA (PostgreSQL, `jsonb`), Spring Kafka, MinIO client, Thumbnailator, STOMP WebSocket.
- **Depends on:** PostgreSQL database `product-catalog`, MinIO bucket `images`, Kafka.
- **Two locks on every request** ([common-security](common-security.md)): the gateway's secret header (403 without it) **and** the user's own JWT, checked again here (401 if missing or invalid). Permission-based: reading categories (and their attributes) needs `category:read`, changing them `category:write`; reading products (variants, images) needs `product:read`, changing them `product:write` (403 otherwise). A customer has the two read permissions, an admin all of them. Swagger (`/swagger-ui.html`, `/v3/api-docs`) is the exception and can be opened directly.

## Data model

```
Category ──(parent/children)── Category
   └─ AttributeDefinition*   key, label, data_type, options, required, filterable
Product   sku (unique), name, category, base_price, cost_price, status DRAFT|ACTIVE|ARCHIVED,
          attributes (jsonb, checked against the category's AttributeDefinitions)
   ├─ ProductVariant*   sku (unique), attributes, price_override, barcode
   └─ ProductImage*     url, thumbnail_url, position (0 = first), status PROCESSING|READY|FAILED
```

Deleting a product deletes its variants and images (cascade). Their files in MinIO are removed afterwards through Kafka.

## Endpoints

GET routes are open to `CUSTOMER` and `ADMIN`; everything else is `ADMIN` only (set in the gateway's routes). Responses use `{success, message, data, correlationId}`.

### Categories and attributes

| Method | Path | What it does |
|---|---|---|
| GET | `/api/categories` | All categories |
| GET | `/api/categories/{id}` | One category |
| POST | `/api/categories` | Create (`name`, optional `parent_id`). 201 |
| PATCH | `/api/categories/{id}` | Rename and/or move under another parent (`parent_id: null` = make it a root) |
| DELETE | `/api/categories/{id}` | Delete; 409 while products still use it |
| GET | `/api/categories/{categoryId}/attributes` | The category's attribute definitions |
| GET | `/api/categories/{categoryId}/attributes/{id}` | One definition |
| POST | `/api/categories/{categoryId}/attributes` | Add (`key`, `label`, `data_type`, `options`, `required`, `filterable`). 201 |
| PUT | `/api/categories/{categoryId}/attributes/{id}` | Replace all fields |
| DELETE | `/api/categories/{categoryId}/attributes/{id}` | Remove |

**Category rules:**
- A category can't be its own parent (`CATEGORY_OWN_PARENT`).
- A category can't be moved under one of its own descendants (`CATEGORY_CYCLE`).

**Attribute rules:**
- `key` is unique within a category (`ATTRIBUTE_KEY_EXISTS`, 409).
- `SELECT` and `MULTISELECT` need `options` (`ATTRIBUTE_OPTIONS_REQUIRED`); other types drop them.

### Products

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/products` | All products |
| GET | `/api/v1/products/{id}` | One product |
| POST | `/api/v1/products` | Create. **Multipart:** part `product` (JSON) + part `images` (one or more files). 201 |
| PATCH | `/api/v1/products/{id}` | Update fields and add images (same multipart). Existing variants are kept |
| DELETE | `/api/v1/products/{id}` | Delete with its variants and images |

`product` JSON: `sku`, `name`, `category_id`, `base_price`, `cost_price`, `status`, `attributes`.

**Attribute validation** (`ProductAttributeValidator`): every key in `attributes` must be defined on the product's category, `required` attributes must be present, and values must match the type. Every problem is listed in one 400 response.

| `data_type` | Valid value |
|---|---|
| `TEXT` | string |
| `NUMBER` | number |
| `BOOLEAN` | `true` / `false` |
| `DATE` | `"yyyy-MM-dd"` |
| `SELECT` | one of `options` |
| `MULTISELECT` | list, each one of `options` |

### Variants

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/products/{productId}/variants` | A product's variants |
| GET | `/api/v1/products/{productId}/variants/{variantId}` | One variant |
| POST | `/api/v1/products/{productId}/variants` | Add (`sku`, `price`, `attributes`, `barcode`). 201 |
| PUT | `/api/v1/products/{productId}/variants/{variantId}` | Replace |
| DELETE | `/api/v1/products/{productId}/variants/{variantId}` | Remove |

- **Price:** a variant without `price` uses the product's `base_price`. The response shows both the override and the effective price.
- **SKU:** unique across all variants (`VARIANT_SKU_EXISTS`, 409).

### Images

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/products/{productId}/images` | Gallery in display order |
| POST | `/api/v1/products/{productId}/images` | Upload one or more files (multipart part `images`). 201 |
| PATCH | `/api/v1/products/{productId}/images/{imageId}` | Move to `position` (0 = first); the others shift |
| DELETE | `/api/v1/products/{productId}/images/{imageId}` | Remove; the gallery is renumbered |

**Upload checks:**
- Every file must be a non-empty `image/*`, otherwise 400 **before anything is stored**.
- File names are made URL-safe (`my photo.png` → `my_photo.png`).
- Size limits are Spring's defaults: 1 MB per file, 10 MB per request; over that → 413.
- MinIO down → 503.

## Image pipeline (Kafka)

```
upload ─▶ MinIO putObject + statObject ─▶ product_images row (PROCESSING) ─▶ commit
                                                                             │ after commit
                                                                             ▼
                                                          Kafka: product-image-uploaded
                                                                             │
                     ImageProcessingConsumer: read original → 300×300 JPEG thumbnail → MinIO
                     → row READY + thumbnail_url → WebSocket /topic/products/{productId}

delete image/product ─▶ row deleted ─▶ commit ─▶ Kafka: product-image-deleted
                     ─▶ consumer removes original + thumbnail from MinIO ─▶ WebSocket "DELETED"
```

- **Events are published only after the database commit,** so the consumer never sees a row that isn't saved yet, and files are never deleted for a rolled-back delete.
- **Publish failures** are logged at ERROR with topic and key, and don't fail the request.
- **Retries:** a failing message goes through `-retry-0` (1 min), `-retry-1` (5 min) and `-retry-2` (25 min), then `<topic>.DLT`.
  - The DLT handler logs once at ERROR, marks an image `FAILED` and notifies the frontend.
  - A message is never processed twice: offsets are committed when forwarded, and an upload for an already `READY` image is skipped.
  - Details: [LOGGING.md](../LOGGING.md#errors-and-exceptions).
- **Unreadable or not an image:** the image is marked `FAILED` straight away; retrying wouldn't help.
- **Correlation id:** the request's correlation id travels in the Kafka header, so consumer logs share the HTTP request's id.

**WebSocket:**
- Connect to `/ws` (STOMP) **with the user's token as a STOMP header on CONNECT**: `Authorization: Bearer <access_token>` (SockJS can't send HTTP headers). The token gets the same checks as HTTP requests and needs `product:read`; otherwise the server answers with a STOMP `ERROR` frame. Then subscribe to `/topic/products/{productId}`.
- Messages look like `{"imageId": ..., "status": "READY", "thumbnailUrl": ...}`, or status `FAILED` / `DELETED`.

**Image URLs** are plain public links (`<minio.url>/images/<object>`). `MinioBucketInitializer` creates the bucket at startup and allows anonymous read only (`s3:GetObject`).

## Errors

Messages about the client's own input are returned as they are (validation fields, "Product not found: {id}", the attribute errors above). Anything from a library, the database, MinIO or Kafka, and any 5xx, gets a general message; the detail is only logged.

| Situation | Status |
|---|---|
| `@Valid` failure (lists fields, never values), bad JSON, wrong id format, not an image | 400 |
| Not found | 404 |
| Duplicate SKU or attribute key, category still in use | 409 |
| File too large | 413 |
| MinIO unavailable | 503 |
| Anything else | 500 |

## Configuration

| Property / env var | Default | Purpose |
|---|---|---|
| `spring.datasource.*` | localhost:5432/product-catalog | Database (`ddl-auto: update`) |
| `spring.kafka.bootstrap-servers` | localhost:9092 | Kafka |
| `minio.url`, `minio.access-key`, `minio.secret-key`, `minio.bucket` | localhost:9000, `images` | MinIO |
| `KAFKA_RETRY_ATTEMPTS`, `KAFKA_RETRY_DELAY_MS`, `KAFKA_RETRY_MULTIPLIER`, `KAFKA_RETRY_MAX_DELAY_MS` | 4, 60000, 5, 1800000 | Retry topics |
| `app.kafka.partitions`, `app.kafka.replicas` | 3, 1 | Topic creation |
| `GATEWAY_SECRET`, `GATEWAY_AUTH_ENABLED` | dev secret, `true` | Gateway-only access |
| `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_ISSUER` | localhost:8080, `Dwell`, `http://localhost:8080/realms/Dwell` | JWT check; see [common-security](common-security.md#configuration) |

## Code map

| Package | Main classes |
|---|---|
| `controller` | `CategoryController`, `AttributeDefinitionController`, `ProductController`, `ProductVariantController`, `ProductImageController` |
| `service.impl` | `CategoryServiceImpl`, `AttributeDefinitionServiceImpl`, `ProductServiceImpl`, `ProductVariantServiceImpl`, `ProductImageServiceImpl` |
| `service.validator` | `ProductAttributeValidator`, `ImageFileValidator` |
| `service` | `KafkaPublisher` |
| `cosumer` | `ImageProcessingConsumer` (thumbnails, file cleanup, DLT handler) |
| `config` | `KafkaRetryConfig`, `KafkaTopicConfig`, `MinioConfig`, `MinioBucketInitializer`, `WebSocketConfig`, `WebSocketAuthInterceptor` (JWT on STOMP CONNECT), `SecurityConfig` (JWT + permission rules), `OpenApiConfig`, `logging/*` (correlation id in Kafka) |
| `exception` | `GlobalExceptionHandler`, `AppException`, `EntityNotFoundException`, `AttributeValidationException` |

## Tests (164)

For how each test works (setup, action and checks), see [testing/product-catalog.md](../testing/product-catalog.md).

Run with `mvn -pl product-catalog test`. **Repository tests need Docker** (Testcontainers starts a real PostgreSQL, because the entities use `jsonb`).

### Controllers (`@WebMvcTest`, service mocked)

Each controller test checks status codes, the response wrapper and validation.

**`CategoryControllerTest`**
- Get, list, create (201), update and delete.
- A malformed UUID → 400; missing → 404.
- An invalid hierarchy move → 400; deleting a category still in use → 409.

**`AttributeDefinitionControllerTest`**
- Create → 201 with `Location`; missing required fields → 400; duplicate key → 409.
- List, get (404 when missing), update, delete (404 when missing).

**`ProductControllerTest`**
- Create → 201 with `Location`, and the uploaded images reach the service.
- The `product` and `images` parts are required; an invalid product → 400.
- Attribute errors → 400; duplicate SKU → 409.
- Get, list, update, delete; 404 when missing.

**`ProductVariantControllerTest`**
- Create → 201; blank SKU or negative price → 400; duplicate SKU → 409.
- List (404 for a missing product), update, delete.

**`ProductImageControllerTest`**
- Upload adds each file → 201; no files → 400.
- Gallery, move (negative or missing position → 400), delete (image not on the product → 404).

### Services (unit tests, repositories and MinIO mocked)

**`CategoryServiceImplTest`** (19)
- Mapping, create as root or under a parent (missing parent → error).
- Rename trims the name, and a null name leaves it unchanged.
- Moving: same parent is a no-op; self-parent and moving under a descendant are rejected; moving between parents updates both child lists; `null` detaches to root.
- Delete.

**`AttributeDefinitionServiceImplTest`** (11)
- Create attaches to the category.
- Options are dropped for non-choice types and required for SELECT/MULTISELECT.
- Duplicate key → conflict; missing category → error.
- A definition from another category is "not found".
- Update replaces everything, skipping the duplicate check when the key is unchanged.

**`ProductServiceImplTest`** (11)
- Create validates attributes, saves, then uploads each image; a missing category or invalid attributes save nothing.
- Update copies every field, adds new images and keeps existing variants.
- Delete removes the product, then publishes one cleanup event per image (nothing published if the product is missing).

**`ProductVariantServiceImplTest`** (11)
- Create with or without price (inherits `base_price`); duplicate SKU → conflict; missing product → error.
- A variant of another product is "not found".
- Update skips the SKU check when the SKU is unchanged; a taken SKU → conflict.

**`ProductImageServiceImplTest`** (12)
- Upload stores the file in MinIO, verifies it, saves it at the next position and publishes the event.
- A MinIO failure → 503 with nothing saved or published.
- Non-image or empty files are rejected before anything is stored; file names are made safe.
- The gallery is in order; moving shifts the others (beyond the end → last).
- Delete renumbers, then publishes; an image not on the product → error and nothing published.

### Kafka

**`ImageProcessingConsumerTest`**
- A duplicate event for an already `READY` image is skipped (no MinIO work).
- An event for a deleted image is skipped.
- The DLT handler marks the image `FAILED` and notifies the frontend once, without republishing; a dead-lettered delete event is only logged.

**`KafkaCorrelationIdTest`**
- The producer copies the request's correlation id into the record header, never overwriting an existing one.
- The consumer runs the listener under that id and clears it afterwards; it generates an id only when the record has none.

### Repositories (`@DataJpaTest`, real PostgreSQL)

- **`ProductRepositoryTest`:** fields and `jsonb` attributes are saved with timestamps; variants and images cascade; images come back ordered by position; deleting cascades; duplicate SKU violates the unique constraint.
- **`CategoryRepositoryTest`:** parent links, attribute definitions cascade with `jsonb` options, and orphans are removed.
- **`AttributeDefinitionRepositoryTest`:** queries are scoped to a category; the same key twice in one category violates the unique constraint.
- **`ProductVariantRepositoryTest`:** scoped to the product; SKU existence check.
- **`ProductImageRepositoryTest`:** new images start `PROCESSING`; `markReady` sets the thumbnail and status, and returns 0 if the image was deleted; gallery order.

### Other

- **`GlobalExceptionHandlerTest`** (12): own messages are kept; JPA, library and database details are replaced; validation lists fields but never values; bad JSON, wrong parameter type, 413, Spring statuses and 503 storage errors get the right status and a safe message.
- **`SecurityConfigTest`:** the real filter chain. A valid token without the gateway secret → 403; the secret without a token → 401 (JSON); `category:read` reads but can't change; `category:write` changes; a realm role without permissions can't read; product permissions don't open categories.
- **`WebSocketAuthInterceptorTest`:** STOMP CONNECT with `product:read` sets the user; without a token, with an invalid one or without `product:read` it's refused; a SUBSCRIBE from an unauthenticated session is refused.
- The gateway-secret filter tests moved to [common-security](common-security.md).
- **`ProductCatalogApplicationTests`:** the Spring context starts.
