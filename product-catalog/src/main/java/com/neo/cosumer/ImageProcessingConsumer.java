package com.neo.cosumer;

import com.neo.entity.ProductImage;
import com.neo.event.ProductImageDeletedEvent;
import com.neo.event.ProductImageUploadedEvent;
import com.neo.repository.ProductImageRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class ImageProcessingConsumer {

  private final MinioClient minioClient;
  private final ProductImageRepository productImageRepository;
  private final SimpMessagingTemplate messagingTemplate;

  @Value("${minio.bucket}")
  private String bucket;

  @Value("${minio.url}")
  private String minioUrl;

  public ImageProcessingConsumer(
      MinioClient minioClient,
      ProductImageRepository productImageRepository,
      SimpMessagingTemplate messagingTemplate) {
    this.minioClient = minioClient;
    this.productImageRepository = productImageRepository;
    this.messagingTemplate = messagingTemplate;
  }

  @RetryableTopic(
      attempts = "${app.kafka.retry.attempts:4}",
      backoff =
          @Backoff(
              delayExpression = "${app.kafka.retry.delay-ms:60000}",
              multiplierExpression = "${app.kafka.retry.multiplier:5}",
              maxDelayExpression = "${app.kafka.retry.max-delay-ms:1800000}"),
      retryTopicSuffix = "-retry",
      dltTopicSuffix = ".DLT",
      topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
      dltStrategy = DltStrategy.FAIL_ON_ERROR,
      numPartitions = "${app.kafka.partitions:3}",
      replicationFactor = "${app.kafka.replicas:1}")
  @KafkaListener(topics = "${app.kafka.image-uploaded.topic}", groupId = "image-processing-group")
  @Transactional
  public void handle(ProductImageUploadedEvent event, Acknowledgment ack) {

    ProductImage image = productImageRepository.findById(event.productImageId()).orElse(null);
    if (image == null) {
      log.info("Image {} already deleted, skipping thumbnail", event.productImageId());
      ack.acknowledge();
      return;
    }
    if (image.getStatus() == ProductImage.ImageStatus.READY) {
      // Kafka delivers at least once: a duplicate must not redo the work
      log.info("Image {} already processed, skipping duplicate", event.productImageId());
      ack.acknowledge();
      return;
    }

    String thumbName = "thumb-" + event.objectName();

    try {
      byte[] bytes;
      try (InputStream original =
          minioClient.getObject(
              GetObjectArgs.builder().bucket(bucket).object(event.objectName()).build())) {

        BufferedImage img = ImageIO.read(original);
        if (img == null) {
          // Not a readable image: retrying won't help
          log.warn(
              "Image {} ({}) is not a readable image; marking it FAILED",
              event.productImageId(),
              event.objectName());
          markFailed(event);
          ack.acknowledge();
          return;
        }
        ByteArrayOutputStream thumbBytes = new ByteArrayOutputStream();
        Thumbnails.of(img).size(300, 300).outputFormat("jpg").toOutputStream(thumbBytes);
        bytes = thumbBytes.toByteArray();
      }

      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(thumbName).stream(
                  new ByteArrayInputStream(bytes), bytes.length, -1)
              .contentType("image/jpeg")
              .build());

      String thumbnailUrl = minioUrl + "/" + bucket + "/" + thumbName;

      // 2. Atomic update: 0 rows means the image was deleted while we worked
      int updated =
          productImageRepository.markReady(
              event.productImageId(), thumbnailUrl, ProductImage.ImageStatus.READY);

      if (updated == 0) {
        removeQuietly(thumbName); // don't leave the orphaned thumbnail behind
        ack.acknowledge();
        return;
      }

      messagingTemplate.convertAndSend(
          "/topic/products/" + event.productId(),
          Map.of(
              "imageId", event.productImageId(), "thumbnailUrl", thumbnailUrl, "status", "READY"));

      ack.acknowledge();

    } catch (ErrorResponseException e) {
      // Original was deleted from MinIO mid-flight: nothing to retry
      if ("NoSuchKey".equals(e.errorResponse().code())) {
        log.info("Original {} gone, image deleted during processing", event.objectName());
        removeQuietly(thumbName);
        ack.acknowledge();
        return;
      }
      throw new RuntimeException("Failed processing image " + event.productImageId(), e);

    } catch (Exception e) {
      throw new RuntimeException("Failed processing image " + event.productImageId(), e);
    }
  }

  /** Retries are exhausted (or the message is unreadable): logged once, never republished. */
  @DltHandler
  public void onDeadLetter(ConsumerRecord<String, Object> record, Acknowledgment ack) {
    Header reason = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
    log.error(
        "Kafka message failed permanently: topic={} partition={} offset={} key={} reason={}",
        record.topic(),
        record.partition(),
        record.offset(),
        record.key(),
        reason == null ? null : new String(reason.value(), StandardCharsets.UTF_8));
    if (record.value() instanceof ProductImageUploadedEvent event) {
      markFailed(event);
    }
    ack.acknowledge();
  }

  public void markFailed(ProductImageUploadedEvent event) {
    if (productImageRepository.updateStatus(event.productImageId(), ProductImage.ImageStatus.FAILED)
        > 0) {
      messagingTemplate.convertAndSend(
          "/topic/products/" + event.productId(),
          Map.of("imageId", event.productImageId(), "status", "FAILED"));
    }
  }

  @RetryableTopic(
      attempts = "${app.kafka.retry.attempts:4}",
      backoff =
          @Backoff(
              delayExpression = "${app.kafka.retry.delay-ms:60000}",
              multiplierExpression = "${app.kafka.retry.multiplier:5}",
              maxDelayExpression = "${app.kafka.retry.max-delay-ms:1800000}"),
      retryTopicSuffix = "-retry",
      dltTopicSuffix = ".DLT",
      topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
      dltStrategy = DltStrategy.FAIL_ON_ERROR,
      numPartitions = "${app.kafka.partitions:3}",
      replicationFactor = "${app.kafka.replicas:1}")
  @KafkaListener(topics = "${app.kafka.image-deleted.topic}", groupId = "product-image-cleanup")
  public void handle(ProductImageDeletedEvent event, Acknowledgment ack) {
    try {
      removeQuietly(event.objectName());
      removeQuietly(event.thumbnailObjectName());

      messagingTemplate.convertAndSend(
          "/topic/products/" + event.productId(),
          Map.of("imageId", event.productImageId(), "status", "DELETED"));

      ack.acknowledge(); // only after both removals succeeded
      log.info("Cleaned up MinIO files for image {}", event.productImageId());

    } catch (Exception e) {
      // Not acked: retried, then sent to the DLT
      throw new RuntimeException("Failed cleaning up image " + event.productImageId(), e);
    }
  }

  private void removeQuietly(String objectName) {
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
    } catch (Exception e) {
      // DB row is already gone; log so orphaned files can be cleaned up later
      log.warn("Failed to delete MinIO object {}; orphaned file needs cleanup", objectName, e);
    }
  }
}
