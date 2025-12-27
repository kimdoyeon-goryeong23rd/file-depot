package com.saltlux.filedepot.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.stereotype.Component;

import com.saltlux.filedepot.config.FileDepotProperties;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class StorageClient {

  private final MinioClient minioClient;
  private final FileDepotProperties properties;

  @PostConstruct
  public void init() {
    try {
      ensureBucketExists();
    } catch (Exception e) {
      log.error("Failed to initialize MinIO bucket", e);
    }
  }

  private void ensureBucketExists() throws Exception {
    boolean exists = minioClient.bucketExists(
        BucketExistsArgs.builder()
            .bucket(properties.getMinio().getBucket())
            .build());

    if (!exists) {
      minioClient.makeBucket(
          MakeBucketArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .build());
      log.info("Created MinIO bucket: {}", properties.getMinio().getBucket());
    }
  }

  public InputStream getObject(String objectName) {
    try {
      return minioClient.getObject(
          GetObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .build());
    } catch (Exception e) {
      log.error("Failed to get object from MinIO: {}", objectName, e);
      throw new RuntimeException("Failed to get object from MinIO", e);
    }
  }

  public byte[] getObjectBytes(String objectName) {
    try (InputStream stream = getObject(objectName)) {
      return stream.readAllBytes();
    } catch (IOException e) {
      log.error("Failed to read object bytes: {}", objectName, e);
      throw new RuntimeException("Failed to read object bytes", e);
    }
  }

  public void removeObject(String objectName) {
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .build());
      log.debug("Removed object from MinIO: {}", objectName);
    } catch (Exception e) {
      log.error("Failed to remove object from MinIO: {}", objectName, e);
      throw new RuntimeException("Failed to remove object from MinIO", e);
    }
  }

  public StatObjectResponse statObject(String objectName) {
    try {
      return minioClient.statObject(
          StatObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .build());
    } catch (Exception e) {
      log.error("Failed to stat object in MinIO: {}", objectName, e);
      throw new RuntimeException("Failed to stat object in MinIO", e);
    }
  }

  public String getPresignedUploadUrl(String objectName, int expirySeconds) {
    try {
      String url = minioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.PUT)
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .expiry(expirySeconds)
              .build());
      log.debug("Generated presigned upload URL for: {}", objectName);
      return url;
    } catch (Exception e) {
      log.error("Failed to generate presigned upload URL: {}", objectName, e);
      throw new RuntimeException("Failed to generate presigned upload URL", e);
    }
  }

  public String getPresignedDownloadUrl(String objectName, int expirySeconds) {
    try {
      String url = minioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.GET)
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .expiry(expirySeconds)
              .build());
      log.debug("Generated presigned download URL for: {}", objectName);
      return url;
    } catch (Exception e) {
      log.error("Failed to generate presigned download URL: {}", objectName, e);
      throw new RuntimeException("Failed to generate presigned download URL", e);
    }
  }
}
