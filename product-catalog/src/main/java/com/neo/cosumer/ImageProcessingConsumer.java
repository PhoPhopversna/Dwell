package com.neo.cosumer;

import com.neo.entity.ProductImage;
import com.neo.event.ProductImageUploadedEvent;
import com.neo.repository.ProductImageRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
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
    try {
      InputStream original =
          minioClient.getObject(
              GetObjectArgs.builder().bucket(bucket).object(event.objectName()).build());

      BufferedImage img = ImageIO.read(original);

      ByteArrayOutputStream thumbBytes = new ByteArrayOutputStream();
      Thumbnails.of(img).size(300, 300).outputFormat("jpg").toOutputStream(thumbBytes);

      String thumbName = "thumb-" + event.objectName();
      byte[] bytes = thumbBytes.toByteArray();

      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(thumbName).stream(
                  new ByteArrayInputStream(bytes), bytes.length, -1)
              .contentType("image/jpeg")
              .build());

      ProductImage productImage =
          productImageRepository.findById(event.productImageId()).orElseThrow();
      productImage.setThumbnailUrl(minioUrl + "/" + bucket + "/" + thumbName);
      productImage.setStatus(ProductImage.ImageStatus.READY);
      productImageRepository.save(productImage);

      messagingTemplate.convertAndSend(
          "/topic/products/" + event.productId(),
          Map.of(
              "imageId", event.productImageId(),
              "thumbnailUrl", productImage.getThumbnailUrl(),
              "status", "READY"));

      ack.acknowledge();

    } catch (Exception e) {
      // rethrow so DefaultErrorHandler applies retry -> DLT
      throw new RuntimeException("Failed processing image " + event.productImageId(), e);
    }
  }
}
