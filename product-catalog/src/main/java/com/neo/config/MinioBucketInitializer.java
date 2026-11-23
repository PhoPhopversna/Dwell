package com.neo.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Ensures the image bucket exists and is anonymously readable. Image URLs are returned to the
 * frontend as plain links ({@code <minio.url>/<bucket>/<object>}), so the browser must be able to
 * GET objects without credentials. Only {@code s3:GetObject} is granted: anonymous users can't list
 * the bucket or write to it.
 */
@Slf4j
@Component
public class MinioBucketInitializer implements ApplicationRunner {

  private static final String PUBLIC_READ_POLICY =
      """
      {
        "Version": "2012-10-17",
        "Statement": [
          {
            "Effect": "Allow",
            "Principal": {"AWS": ["*"]},
            "Action": ["s3:GetObject"],
            "Resource": ["arn:aws:s3:::%s/*"]
          }
        ]
      }
      """;

  private final MinioClient minioClient;

  @Value("${minio.bucket}")
  private String bucket;

  public MinioBucketInitializer(MinioClient minioClient) {
    this.minioClient = minioClient;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        log.info("Created MinIO bucket {}", bucket);
      }
      minioClient.setBucketPolicy(
          SetBucketPolicyArgs.builder()
              .bucket(bucket)
              .config(PUBLIC_READ_POLICY.formatted(bucket))
              .build());
      log.info("Applied public-read policy to MinIO bucket {}", bucket);
    } catch (Exception e) {
      // Don't block startup if MinIO is down; uploads will fail loudly on their own.
      log.warn(
          "Could not initialise MinIO bucket {}; images may not be publicly readable", bucket, e);
    }
  }
}
