package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neo.dto.ProductImageResponse;
import com.neo.entity.Product;
import com.neo.entity.ProductImage;
import com.neo.event.ProductImageDeletedEvent;
import com.neo.event.ProductImageUploadedEvent;
import com.neo.mapper.ProductMapper;
import com.neo.repository.ProductImageRepository;
import com.neo.repository.ProductRepository;
import com.neo.service.KafkaPublisher;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceImplTest {

  private static final String BUCKET = "images";
  private static final String MINIO_URL = "http://minio:9000";

  @Mock private MinioClient minioClient;
  @Mock private ProductRepository productRepository;
  @Mock private ProductImageRepository productImageRepository;
  @Mock private KafkaPublisher kafkaPublisher;
  @Mock private ProductMapper productMapper;
  @Mock private TransactionTemplate transactionTemplate;
  @InjectMocks private ProductImageServiceImpl service;

  private Product product;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "bucket", BUCKET);
    ReflectionTestUtils.setField(service, "minioUrl", MINIO_URL);
    product = new Product("LP-14", "Laptop", null, BigDecimal.TEN);
    product.setId(UUID.randomUUID());
  }

  private ProductImage galleryImage(String name) {
    ProductImage img = new ProductImage(MINIO_URL + "/" + BUCKET + "/" + name, 0);
    img.setId(UUID.randomUUID());
    product.addImage(img);
    product.getImages().forEach(i -> i.setPosition(product.getImages().indexOf(i)));
    return img;
  }

  private void stubProduct() {
    when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
  }

  private void runTransactionsInline() {
    when(transactionTemplate.execute(any()))
        .thenAnswer(
            inv ->
                inv.<TransactionCallback<?>>getArgument(0)
                    .doInTransaction(mock(TransactionStatus.class)));
  }

  @Test
  void addImageUploadsVerifiesSavesAtNextPositionAndPublishes() throws Exception {
    galleryImage("existing.png");
    stubProduct();
    when(productImageRepository.save(any(ProductImage.class)))
        .thenAnswer(
            inv -> {
              ProductImage img = inv.getArgument(0);
              img.setId(UUID.randomUUID());
              return img;
            });
    ProductImageResponse mapped = new ProductImageResponse();
    when(productMapper.toResponse(any(ProductImage.class))).thenReturn(mapped);

    ProductImageResponse result =
        service.addImageToProduct(
            product.getId(),
            new MockMultipartFile("images", "photo.png", "image/png", new byte[4]));

    assertThat(result).isSameAs(mapped);
    ArgumentCaptor<PutObjectArgs> put = ArgumentCaptor.forClass(PutObjectArgs.class);
    verify(minioClient).putObject(put.capture());
    String objectName = put.getValue().object();
    assertThat(objectName).endsWith("-photo.png");
    assertThat(put.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(put.getValue().contentType()).isEqualTo("image/png");
    verify(minioClient).statObject(any(StatObjectArgs.class));

    ProductImage added = product.getImages().get(1);
    assertThat(added.getPosition()).isEqualTo(1);
    assertThat(added.getUrl()).isEqualTo(MINIO_URL + "/" + BUCKET + "/" + objectName);
    assertThat(added.getProduct()).isSameAs(product);
    // No transaction is active in a unit test, so the event is published immediately.
    verify(kafkaPublisher)
        .publishImageUploaded(
            added.getId().toString(),
            new ProductImageUploadedEvent(added.getId(), product.getId(), objectName));
  }

  @Test
  void addImageUploadFailureSavesAndPublishesNothing() throws Exception {
    stubProduct();
    when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new IOException("down"));

    assertThatThrownBy(
            () ->
                service.addImageToProduct(
                    product.getId(),
                    new MockMultipartFile("images", "a.png", "image/png", new byte[1])))
        .isInstanceOf(com.neo.exception.AppException.class)
        .hasCauseInstanceOf(IOException.class)
        .extracting(e -> ((com.neo.exception.AppException) e).getStatus())
        .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    verify(productImageRepository, never()).save(any());
    verifyNoInteractions(kafkaPublisher);
    assertThat(product.getImages()).isEmpty();
  }

  @Test
  void addImageToMissingProductThrows() {
    UUID id = UUID.randomUUID();
    when(productRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.addImageToProduct(
                    id, new MockMultipartFile("images", "a.png", "image/png", new byte[1])))
        .isInstanceOf(EntityNotFoundException.class);
    verifyNoInteractions(minioClient);
  }

  @Test
  void getImagesReturnsGalleryInOrder() {
    ProductImage a = galleryImage("a.png");
    when(productRepository.existsById(product.getId())).thenReturn(true);
    when(productImageRepository.findByProductIdOrderByPositionAsc(product.getId()))
        .thenReturn(List.of(a));
    ProductImageResponse mapped = new ProductImageResponse();
    when(productMapper.toResponse(a)).thenReturn(mapped);

    assertThat(service.getImages(product.getId())).containsExactly(mapped);
  }

  @Test
  void getImagesOfMissingProductThrows() {
    UUID id = UUID.randomUUID();
    when(productRepository.existsById(id)).thenReturn(false);

    assertThatThrownBy(() -> service.getImages(id)).isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void updatePositionMovesImageAndShiftsOthers() {
    ProductImage a = galleryImage("a.png");
    ProductImage b = galleryImage("b.png");
    ProductImage c = galleryImage("c.png");
    stubProduct();

    service.updatePosition(product.getId(), c.getId(), 0);

    assertThat(List.of(c.getPosition(), a.getPosition(), b.getPosition())).containsExactly(0, 1, 2);
  }

  @Test
  void updatePositionBeyondEndMovesImageLast() {
    ProductImage a = galleryImage("a.png");
    ProductImage b = galleryImage("b.png");
    stubProduct();

    service.updatePosition(product.getId(), a.getId(), 99);

    assertThat(b.getPosition()).isZero();
    assertThat(a.getPosition()).isEqualTo(1);
  }

  @Test
  void updatePositionOfUnknownImageThrows() {
    galleryImage("a.png");
    stubProduct();

    assertThatThrownBy(() -> service.updatePosition(product.getId(), UUID.randomUUID(), 0))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining("Image not found");
  }

  @Test
  void deleteImageRemovesRowRenumbersGalleryThenPublishes() {
    runTransactionsInline();
    ProductImage a = galleryImage("a.png");
    ProductImage b = galleryImage("b.png");
    stubProduct();

    service.deleteImage(product.getId(), a.getId());

    var order = org.mockito.Mockito.inOrder(productImageRepository, kafkaPublisher);
    order.verify(productImageRepository).delete(a);
    order
        .verify(kafkaPublisher)
        .deleteImage(
            eq(a.getId().toString()),
            eq(new ProductImageDeletedEvent(a.getId(), product.getId(), "a.png", "thumb-a.png")));
    assertThat(product.getImages()).containsExactly(b);
    assertThat(b.getPosition()).isZero();
  }

  @Test
  void deleteImageNotOnProductThrowsAndPublishesNothing() {
    runTransactionsInline();
    galleryImage("a.png");
    stubProduct();

    assertThatThrownBy(() -> service.deleteImage(product.getId(), UUID.randomUUID()))
        .isInstanceOf(EntityNotFoundException.class);
    verify(productImageRepository, never()).delete(any());
    verifyNoInteractions(kafkaPublisher);
  }

  @Test
  void nonImageOrEmptyFileIsRejectedBeforeAnythingIsStored() {
    for (MockMultipartFile bad :
        List.of(
            new MockMultipartFile("images", "notes.txt", "text/plain", new byte[] {1}),
            new MockMultipartFile("images", "a.png", "image/png", new byte[0]),
            new MockMultipartFile("images", "x", null, new byte[] {1}))) {
      assertThatThrownBy(() -> service.addImageToProduct(UUID.randomUUID(), bad))
          .isInstanceOf(com.neo.exception.AppException.class)
          .extracting(e -> ((com.neo.exception.AppException) e).getStatus())
          .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }
    verifyNoInteractions(minioClient, productRepository);
  }

  @Test
  void uploadedFileNameIsMadeSafeForTheObjectNameAndUrl() {
    assertThat(com.neo.service.validator.ImageFileValidator.safeFileName("../my photo (1).png"))
        .isEqualTo("my_photo__1_.png");
    assertThat(com.neo.service.validator.ImageFileValidator.safeFileName("C:\\x\\a.jpg"))
        .isEqualTo("a.jpg");
    assertThat(com.neo.service.validator.ImageFileValidator.safeFileName(null)).isEqualTo("image");
  }
}
