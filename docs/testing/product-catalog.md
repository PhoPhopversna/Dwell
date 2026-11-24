# product-catalog tests (157)

`mvn -pl product-catalog test`. **Repository tests need Docker** (Testcontainers PostgreSQL); `ProductCatalogApplicationTests` needs your local PostgreSQL. See the [testing guide](README.md) for the patterns used. Service behaviour: [product-catalog](../services/product-catalog.md).

| Layer | Classes | Tests |
|---|---|---|
| [Controllers](#controllers-webmvctest) | 5 | 45 |
| [Services](#services-mockito) | 5 | 64 |
| [Repositories](#repositories-datajpatest-on-real-postgresql) | 5 | 22 |
| [Kafka, errors, security, startup](#kafka-errors-security-startup) | 5 | 26 |

---

## Controllers (`@WebMvcTest`)

Spring starts only the controller, JSON conversion, validation and `GlobalExceptionHandler`; the service is a `@MockitoBean`. Each test sends a real HTTP request with `MockMvc` and checks the status, headers and JSON. "Service never called" (`verifyNoInteractions`) proves validation rejected the request first.

### `CategoryControllerTest` (9)

| Test | Request | Checks |
|---|---|---|
| `getByIdReturnsCategory` | `GET /api/categories/{id}` | 200; `data.id`, `data.name`, `data.parent_id` |
| `getByIdRejectsMalformedUuid` | `GET /api/categories/123` | 400 |
| `listReturnsAllCategories` | `GET /api/categories` | 200; two items in order |
| `postDeserializesRequestAndReturns201` | `POST` with `parent_id`, `name`, one attribute definition | 201, `Location` header; the service got the right name, parent and attribute (`ArgumentCaptor`) |
| `patchReturnsUpdatedCategory` | `PATCH {"name":"Renamed"}` | 200 with the new name |
| `deleteReturnsConfirmationText` | `DELETE` | 200 `data = "Delete Successfully"`; service called with the id |
| `getByIdReturns404WhenMissing` | Service throws not-found | 404, `success:false` |
| `patchReturns400ForInvalidHierarchyMove` | Service throws `CATEGORY_OWN_PARENT` | 400 with that message |
| `deleteReturns409WhenCategoryStillInUse` | Service throws `DataIntegrityViolationException` (FK) | 409 |

### `AttributeDefinitionControllerTest` (8)

Base path `/api/categories/{categoryId}/attributes`; body `{"key":"size","label":"Size","data_type":"SELECT","options":["S","M"]}`.

| Test | Request | Checks |
|---|---|---|
| `createReturns201WithLocation` | `POST` valid body | 201, `Location`, `data.key`, `data.data_type` |
| `createRejectsMissingRequiredFields` | `POST {"key":"x"}` | 400, service never called |
| `createDuplicateKeyReturns409` | Service throws `ATTRIBUTE_KEY_EXISTS` | 409 with the message |
| `getAllReturnsList` | `GET` | 200, two items |
| `getByIdNotFoundReturns404` | Service throws not-found | 404 |
| `updateReturnsUpdatedDefinition` | `PUT` | 200 |
| `deleteDelegatesToService` | `DELETE` | 200; service called with category and id |
| `deleteNotFoundReturns404` | Service throws not-found | 404 |

### `ProductControllerTest` (14)

Create and update are **multipart**: part `product` (JSON) plus part `images` (files). The `ProductMapper` is also mocked.

| Test | Request | Checks |
|---|---|---|
| `createReturns201WithLocationAndWrappedBody` | Valid `product` + one image | 201, `Location`, `success:true`, `data.sku`, `data.category_id`, `data.base_price` |
| `createPassesUploadedImagesToService` | Two image parts | The service gets a list of 2 files |
| `createRejectsInvalidProductWith400` | Blank SKU and name, negative price | 400, service never called |
| `createRequiresImagesPart` | No `images` part | 400 |
| `createRequiresProductPart` | No parts at all | 400 |
| `updateDelegatesToServiceAndReturnsWrappedBody` | Multipart `PATCH` | 200, wrapped body |
| `updateRejectsInvalidProductWith400` | `PATCH` with only `sku` | 400, service never called |
| `deleteReturnsSuccessMessage` | `DELETE` | 200 `Delete Successfully` |
| `attributeValidationErrorIsReturnedAs400` | Service throws `AttributeValidationException(["bad"])` | 400 `Attribute validation failed: bad` |
| `duplicateSkuIsReturnedAs409` | Service throws `DataIntegrityViolationException` | 409 |
| `getByIdReturnsWrappedProduct` | `GET /{id}` | 200, wrapped product |
| `getByIdReturns404WhenMissing` | Not found | 404 with `Product not found: {id}` |
| `getAllReturnsWrappedList` | `GET` | 200, two products in order |
| `deleteReturns404WhenMissing` | Not found on delete | 404 |

### `ProductVariantControllerTest` (7)

Base path `/api/v1/products/{productId}/variants`; body `{"sku":"TS-1-M","price":25.00,"attributes":{"size":"M"}}`.

| Test | Request | Checks |
|---|---|---|
| `createReturns201WithLocation` | `POST` | 201, `Location`, `data.sku`, `data.effective_price` |
| `createRejectsBlankSkuAndNegativePrice` | `{"sku":"","price":-1}` | 400, service never called |
| `createDuplicateSkuReturns409` | Service throws `VARIANT_SKU_EXISTS` | 409 |
| `getAllReturnsList` | `GET` | 200, one item |
| `getAllOfMissingProductReturns404` | Product not found | 404 |
| `updateReturnsUpdatedVariant` | `PUT` | 200 |
| `deleteDelegatesToService` | `DELETE` | 200; service called |

### `ProductImageControllerTest` (7)

| Test | Request | Checks |
|---|---|---|
| `uploadAddsEachFileAndReturns201` | Multipart with 2 images | 201, two items (positions 0 and 1, status `PROCESSING`); service called twice |
| `uploadWithoutFilesReturns400` | Multipart with no files | 400, service never called |
| `getAllReturnsGallery` | `GET` | 200, two items |
| `patchMovesImage` | `PATCH {"position":2}` | 200, `data.position = 2` |
| `patchRejectsNegativeOrMissingPosition` | `{"position":-1}`, then `{}` | 400 both times, service never called |
| `deleteDelegatesToService` | `DELETE` | 200 `Delete Successfully` |
| `deleteImageNotOnProductReturns404` | Service throws `Image not found` | 404 |

---

## Services (Mockito)

`@ExtendWith(MockitoExtension.class)` with `@Mock` dependencies and `@InjectMocks` service. The entities are real objects, so tests check the actual state changes (parents, child lists, positions) as well as the calls made.

### `CategoryServiceImplTest` (19)

Grouped with `@Nested` by method. A helper builds categories with real parent and child links; `stubToResponse` maps entities to responses.

| Test | How it works | Checks |
|---|---|---|
| **getCategories** `mapsEveryCategory` | Repository has A, B | Responses A, B in order |
| `emptyRepositoryReturnsEmptyList` | Repository empty | Empty list |
| **getCategoryById** `returnsMappedCategory` | Found | Same id |
| `throwsWhenMissing` | Not found | `EntityNotFoundException` naming the id |
| **saveCategory** `savesRootCategory` | No parent | Saved; `parentId` null |
| `attachesToExistingParent` | Parent exists | Child's parent set **and** added to the parent's children |
| `failsWhenParentMissing` | Parent id unknown | `Parent category not found`; nothing saved |
| **updateCategory** `throwsWhenCategoryMissing` | Unknown id | Not found |
| `trimsAndUpdatesName` | Name `"  New  "` | Saved as `New` |
| `nullNameLeavesNameUnchanged` | Name null | Name kept |
| `sameParentIsANoOpForHierarchy` | Same parent id | Nothing moved; the parent isn't even looked up |
| `rejectsSelfAsParent` | Parent id = own id | `A category cannot be its own parent` |
| `rejectsMovingUnderOwnDescendant` | Root → its grandchild | `Cannot move a category under one of its own descendants`; root unchanged |
| `failsWhenNewParentMissing` | New parent id unknown | Not found |
| `movesBetweenParentsAndUpdatesBothChildLists` | Old parent → new parent | Removed from the old parent's children, added to the new one's |
| `nullParentIdDetachesToRoot` | `parent_id` null | Parent null; the old parent has no children |
| `rootCategoryCanBeAttachedToParent` | Root → parent | Attached both ways |
| **deleteCategory** `deletesExistingCategory` | Found | `repository.delete` called |
| `throwsWhenMissing` | Not found | Not found; nothing deleted |

### `AttributeDefinitionServiceImplTest` (11)

Uses the **real** MapStruct mapper (`@Spy AttributeDefinitionMapperImpl`), so mapping is tested too. Category "Shirts" is the test fixture.

| Test | How it works | Checks |
|---|---|---|
| `createAttachesDefinitionToCategory` | SELECT `size` with options, required | Response fields; the definition is added to the category with its back-link |
| `createDropsOptionsForNonChoiceTypes` | TEXT with options | Options null |
| `createRejectsChoiceTypeWithoutOptions` | MULTISELECT with no options | 400; nothing saved |
| `createRejectsDuplicateKeyInCategory` | Key already exists in the category | 409 |
| `createInMissingCategoryThrows` | Unknown category | Not found |
| `getAllListsCategoryDefinitions` | One definition | Its key |
| `getByIdFromAnotherCategoryIsNotFound` | Lookup scoped to the category finds nothing | Not found (can't read another category's definition) |
| `updateReplacesAllFields` | `color` → `colour`, SELECT, options, filterable | Every field replaced |
| `updateKeepingSameKeySkipsDuplicateCheck` | Same key | Duplicate check never called |
| `updateToKeyUsedByAnotherDefinitionConflicts` | Key taken by another definition | 409 |
| `deleteRemovesFromCategoryAndRepository` | Delete | Removed from the category's list; `repository.delete` called |

### `ProductServiceImplTest` (11)

All dependencies mocked, including `ProductAttributeValidator`, `ProductImageService` and `KafkaPublisher`. `TransactionTemplate` runs its callback straight away.

| Test | How it works | Checks |
|---|---|---|
| **create** `validatesMapsSavesAndUploadsEachImage` | Valid request + 2 images | Attributes validated against the category; category set; saved; images uploaded **in order** (`inOrder`) |
| `failsWhenCategoryMissing` | Unknown category | `Category not found`; nothing validated, saved or uploaded |
| `attributeValidationFailureSavesNothing` | Validator throws | Error returned; nothing saved or uploaded |
| **get** `getByIdReturnsProduct` / `getByIdThrowsWhenMissing` / `getProductsReturnsAll` | Found / not found / list | Product / not found with the id / all |
| **update** `copiesAllRequestFieldsOntoExistingProduct` | New name, SKU, prices, status, attributes, **new category** | The same entity (same id) gets every field; validated against the new category |
| `uploadsNewImagesAndKeepsExistingVariants` | Product with a variant; one new image | Image uploaded; the variants list is the **same object**, still 1 item (replacing it would delete every variant through orphan removal) |
| `failsWhenProductMissing` | Unknown product | `Product not found`; not validated |
| **delete** `deletesProductThenPublishesCleanupEventPerImage` | Product with 2 images | Row deleted **first**, then one `ProductImageDeletedEvent` per image with the right object and thumbnail names |
| `missingProductThrowsAndPublishesNothing` | Unknown product | Not found; nothing deleted or published |

### `ProductVariantServiceImplTest` (11)

Product `TS-1` with base price 20.00.

| Test | How it works | Checks |
|---|---|---|
| `createAddsVariantToProduct` | SKU, price 25.00, attributes, barcode | Variant linked to the product with every field |
| `createWithoutPriceInheritsBasePrice` | No price | Effective price 20.00 |
| `createRejectsDuplicateSku` | SKU exists | 409; nothing saved |
| `createOnMissingProductThrows` | Unknown product | Not found |
| `getAllListsVariantsOfProduct` | One variant | Mapped list |
| `getAllOfMissingProductThrows` | Product doesn't exist | Not found |
| `getByIdOfVariantOnAnotherProductIsNotFound` | Lookup scoped to the product | `Variant not found` |
| `updateReplacesFields` | New SKU and price | Saved with them (`ArgumentCaptor`) |
| `updateKeepingSkuSkipsDuplicateCheck` | Same SKU | Duplicate check never called |
| `updateToTakenSkuConflicts` | SKU taken | Error; nothing saved |
| `deleteRemovesFromProductAndRepository` | Delete | Removed from the product; `repository.delete` called |

### `ProductImageServiceImplTest` (12)

MinIO, repositories, `KafkaPublisher` and the mapper are mocked; bucket `images`, URL `http://minio:9000`. A helper builds a gallery on a real `Product`.

| Test | How it works | Checks |
|---|---|---|
| `addImageUploadsVerifiesSavesAtNextPositionAndPublishes` | Gallery has 1 image; upload `photo.png` | `putObject` to `images` with `image/png`, object name ending `-photo.png`; `statObject` verifies it; new image at position 1 with the right URL; `ProductImageUploadedEvent` published |
| `addImageUploadFailureSavesAndPublishesNothing` | `putObject` throws `IOException` | `AppException` 503 caused by the IOException; nothing saved or published; gallery unchanged |
| `addImageToMissingProductThrows` | Unknown product | Not found; MinIO never called |
| `getImagesReturnsGalleryInOrder` / `getImagesOfMissingProductThrows` | Found / not found | Ordered gallery / not found |
| `updatePositionMovesImageAndShiftsOthers` | a, b, c; move c to 0 | Positions c=0, a=1, b=2 |
| `updatePositionBeyondEndMovesImageLast` | a, b; move a to 99 | b=0, a=1 |
| `updatePositionOfUnknownImageThrows` | Unknown image | `Image not found` |
| `deleteImageRemovesRowRenumbersGalleryThenPublishes` | a, b; delete a | Row deleted, **then** the delete event published; gallery is [b] with b at position 0 |
| `deleteImageNotOnProductThrowsAndPublishesNothing` | Unknown image | Not found; nothing deleted or published |
| `nonImageOrEmptyFileIsRejectedBeforeAnythingIsStored` | `text/plain` file; empty png; no content type | 400 each time; MinIO and the database never touched |
| `uploadedFileNameIsMadeSafeForTheObjectNameAndUrl` | `../my photo (1).png`, a Windows path, `null` | `my_photo__1_.png`, `a.jpg`, `image` |

---

## Repositories (`@DataJpaTest` on real PostgreSQL)

All extend `AbstractRepositoryTest`: one `postgres:16-alpine` container for every repository test, schema created from the entities (`create-drop`). Data is written with `TestEntityManager`, then `flush()` + `clear()`, so reads really come from the database.

| Class | Test | Checks |
|---|---|---|
| `ProductRepositoryTest` (4) | `savePersistsFieldsJsonbAttributesAndTimestamps` | SKU, price, status, category; `jsonb` attributes keep their types (string, int, list); timestamps set |
| | `variantsAndImagesCascadeAndImagesAreOrderedByPosition` | Variant saved through the product; images saved out of order come back 0, 1 |
| | `deletingProductCascadesToImagesAndVariants` | Image and variant tables empty after deleting the product |
| | `duplicateSkuViolatesUniqueConstraint` | Second product with the same SKU → `DataIntegrityViolationException` |
| `CategoryRepositoryTest` (6) | `findByUuidReturnsSavedCategoryWithParent` | Name and parent loaded |
| | `findByUnknownUuidIsEmpty` | Empty |
| | `findAllReturnsEveryCategory` | Both categories |
| | `attributeDefinitionsCascadeAndPersistJsonbOptions` | Definition saved through the category; `jsonb` options and `required` kept |
| | `removingAttributeDefinitionDeletesOrphan` | Removing from the list deletes the row |
| | `builtInIdMethodsUseUuid` | `existsById` / `deleteById` work with UUID ids |
| `AttributeDefinitionRepositoryTest` (4) | `findByCategoryIdReturnsOnlyThatCategory` | Shirts' `size` and `color`, not Shoes' `size` |
| | `findByIdAndCategoryIdScopesToCategory` | Found with the right category, empty with the wrong one |
| | `existsByCategoryIdAndKeyIsPerCategory` | `color` exists for Shirts, not Shoes |
| | `sameKeyTwiceInOneCategoryViolatesUniqueConstraint` | Duplicate key in one category → constraint error |
| `ProductVariantRepositoryTest` (3) | `findByProductIdReturnsOnlyThatProductsVariants` | Shirt's variant with attributes, price override, barcode |
| | `findByIdAndProductIdScopesToProduct` | Not found through another product |
| | `existsBySku` | true / false |
| `ProductImageRepositoryTest` (5) | `newImagesStartInProcessingState` | Status `PROCESSING`, no thumbnail |
| | `markReadyUpdatesThumbnailAndStatus` | Returns 1; status `READY` with thumbnail URL |
| | `markReadyReturnsZeroWhenImageNoLongerExists` | Returns 0 (the consumer uses this to detect a deleted image) |
| | `existsByIdReflectsDeletion` | true, then false after delete |
| | `findByProductIdOrderByPositionAscReturnsGalleryOrder` | Positions 0, 1, 2 even when added out of order |

---

## Kafka, errors, security, startup

### `ImageProcessingConsumerTest` (4)

Calls the listener methods directly with mocked MinIO, repository and WebSocket template.

| Test | How it works | Checks |
|---|---|---|
| `duplicateOfAnAlreadyProcessedImageIsSkipped` | The image is already `READY` | MinIO and WebSocket never touched; `markReady` not called; acknowledged. This is the "never process twice" guarantee |
| `deletedImageIsSkipped` | The image row is gone | MinIO not touched; acknowledged |
| `deadLetterMarksTheImageFailedOnceAndNeverRepublishes` | DLT record (`product-image-uploaded.DLT`) with an upload event and an exception header | Status set to `FAILED`; frontend notified on `/topic/products/{id}`; acknowledged |
| `deadLetteredDeleteEventIsOnlyLogged` | DLT record with a delete event | No status change; acknowledged |

### `KafkaCorrelationIdTest` (4)

Calls the producer and consumer interceptors directly with hand-built records.

| Test | How it works | Checks |
|---|---|---|
| `producerCopiesRequestIdFromMdcIntoRecordHeader` | MDC `req-42`, send a record | Header `X-Correlation-ID: req-42` |
| `producerNeverOverwritesExistingHeaderAndSkipsWhenNoRequestContext` | Record already has a header; then an empty MDC | Original header kept; no header added without a request |
| `endToEnd_consumerRunsListenerWithTheProducersIdAndClearsItAfterwards` | Produce with `trace-e2e`, clear the MDC (another thread), consume the same headers | Listener runs with `trace-e2e`; MDC empty afterwards |
| `consumerGeneratesIdOnlyForRecordsWithoutOne` | Old record without a header | A new valid id while processing; cleared after a failure too |

### `GlobalExceptionHandlerTest` (12)

Calls the handler methods directly and reads the `CustomApiResponse` message.

| Test | How it works | Checks |
|---|---|---|
| `ownNotFoundMessagesAreKept` | `Product not found: {id}`, `Image not found` | Message returned as-is (404) |
| `jpaInternalNotFoundMessagesAreReplaced` | JPA's `Unable to find com.neo.entity.Product with id ...` | `Resource not found`, no package name |
| `libraryIllegalArgumentMessagesAreReplaced` | `IllegalArgumentException` from a library | 400 `Invalid request` |
| `ownBusinessRulesAreKept` | Category cycle error; attribute validation error | Messages returned |
| `databaseDetailsAreNeverReturned` | Duplicate key SQL message | 409; no constraint name or SQL |
| `serverErrorsAreAlwaysGeneral` | 500 mentioning MinIO's URL | `An unexpected error occurred` |
| `validationErrorsListTheFieldsButNotTheRejectedValues` | `@Valid` failure on `name` with value `s3cr3t-value` | `Validation failed: name: must not be blank`; value not shown |
| `malformedJsonGetsAGeneralMessage` | JSON parse error | 400 `Malformed request body` |
| `wrongParameterTypeNamesTheParameterOnly` | `abc` for a UUID `id` | 400 `Invalid value for 'id'` |
| `tooLargeUploadIs413` | `MaxUploadSizeExceededException` | 413 `Uploaded file is too large` |
| `springExceptionsKeepTheirStatus_unknownExceptionsAre500WithAGeneralMessage` | 405 exception; `IllegalStateException` with a DB host | 405 `Method not allowed`; 500 without the host |
| `storageFailureIs5xxWithAGeneralMessage` | MinIO 503 `AppException` | 503, general message |

### `GatewayAuthFilterTest` (5)

Servlet version of the gateway-only check. Secret `s3cret`, excluded path `/v3/api-docs/**`. `chain.getRequest()` is null when the request was stopped.

| Test | How it works | Checks |
|---|---|---|
| `allowsRequestWithGatewaySecret` | Correct header | Passed, 200 |
| `rejectsDirectRequestWithoutHeader` | No header | 403 with a JSON error body; not passed |
| `rejectsRequestWithWrongSecret` | Wrong header | 403 |
| `allowsExcludedPathWithoutHeader` | `/v3/api-docs/swagger-config` | Passed (Swagger works directly) |
| `failsFastWhenSecretMissing` | Blank secret | `IllegalStateException` at creation |

### `ProductCatalogApplicationTests` (1)

| Test | How it works | Checks |
|---|---|---|
| `contextLoads` | `@SpringBootTest`: starts the whole application with the real config | Every bean wires up. Needs local PostgreSQL |
