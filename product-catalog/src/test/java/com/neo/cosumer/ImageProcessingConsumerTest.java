package com.neo.cosumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neo.entity.ProductImage;
import com.neo.event.ProductImageDeletedEvent;
import com.neo.event.ProductImageUploadedEvent;
import com.neo.repository.ProductImageRepository;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ImageProcessingConsumerTest {

  private final MinioClient minio = mock(MinioClient.class);
  private final ProductImageRepository images = mock(ProductImageRepository.class);
  private final SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
  private final ImageProcessingConsumer consumer = new ImageProcessingConsumer(minio, images, ws);
  private final Acknowledgment ack = mock(Acknowledgment.class);

  private final UUID imageId = UUID.randomUUID();
  private final ProductImageUploadedEvent uploaded =
      new ProductImageUploadedEvent(imageId, UUID.randomUUID(), "a.png");

  private ProductImage imageWithStatus(ProductImage.ImageStatus status) {
    ProductImage image = new ProductImage("http://minio/images/a.png", 0);
    ReflectionTestUtils.setField(image, "status", status);
    return image;
  }

  @Test
  void duplicateOfAnAlreadyProcessedImageIsSkipped() {
    when(images.findById(imageId))
        .thenReturn(Optional.of(imageWithStatus(ProductImage.ImageStatus.READY)));

    consumer.handle(uploaded, ack);

    verifyNoInteractions(minio, ws);
    verify(images, never()).markReady(any(), anyString(), any());
    verify(ack).acknowledge();
  }

  @Test
  void deletedImageIsSkipped() {
    when(images.findById(imageId)).thenReturn(Optional.empty());

    consumer.handle(uploaded, ack);

    verifyNoInteractions(minio);
    verify(ack).acknowledge();
  }

  @Test
  void deadLetterMarksTheImageFailedOnceAndNeverRepublishes() {
    when(images.updateStatus(imageId, ProductImage.ImageStatus.FAILED)).thenReturn(1);
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("product-image-uploaded.DLT", 0, 7L, imageId.toString(), uploaded);
    record
        .headers()
        .add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "MinIO down".getBytes(StandardCharsets.UTF_8));

    consumer.onDeadLetter(record, ack);

    verify(images).updateStatus(imageId, ProductImage.ImageStatus.FAILED);
    verify(ws).convertAndSend(eq("/topic/products/" + uploaded.productId()), any(Object.class));
    verify(ack).acknowledge();
  }

  @Test
  void deadLetteredDeleteEventIsOnlyLogged() {
    ProductImageDeletedEvent deleted =
        new ProductImageDeletedEvent(imageId, UUID.randomUUID(), "a.png", "thumb-a.png");

    consumer.onDeadLetter(
        new ConsumerRecord<>("product-image-deleted.DLT", 0, 3L, imageId.toString(), deleted), ack);

    verify(images, never()).updateStatus(any(), any());
    verify(ack).acknowledge();
  }
}
