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
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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

  @KafkaListener(topics = "${app.kafka.image-uploaded.topic}", groupId = "image-processing-group")
  @Transactional
  public void handle(ProductImageUploadedEvent event, Acknowledgment ack) {

    // 1. Skip early if the image was deleted before we started
    if (!productImageRepository.existsById(event.productImageId())) {
      log.info("Image {} already deleted, skipping thumbnail", event.productImageId());
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

  @KafkaListener(
      topics = "${app.kafka.image-deleted.topic}",
      groupId = "product-image-cleanup")
  public void handle(ProductImageDeletedEvent event, Acknowledgment ack) {
    try {
      removeQuietly(event.objectName());
      removeQuietly(event.thumbnailObjectName());

      // Tell the frontend right away; the file cleanup doesn't need to block the UI
      messagingTemplate.convertAndSend(
          "/topic/products/" + event.productId(),
          Map.of("imageId", event.productImageId(), "status", "DELETED"));

      ack.acknowledge(); // only after both removals succeeded
      log.info("Cleaned up MinIO files for image {}", event.productImageId());

    } catch (Exception e) {
      // don't ack; rethrow so DefaultErrorHandler applies retry -> DLT
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
