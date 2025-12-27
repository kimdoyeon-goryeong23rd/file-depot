package com.saltlux.filedepot.support;

import java.io.ByteArrayInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.saltlux.filedepot.config.FileDepotProperties;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class TestStorageHelper {

  @Autowired
  private MinioClient minioClient;

  @Autowired
  private FileDepotProperties properties;

  public void putObject(String objectName, byte[] content, String contentType) {
    try {
      minioClient.putObject(
          PutObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .stream(new ByteArrayInputStream(content), content.length, -1)
              .contentType(contentType)
              .build());
      log.debug("Test: Uploaded content to MinIO: {}", objectName);
    } catch (Exception e) {
      throw new RuntimeException("Test: Failed to upload content to MinIO", e);
    }
  }

  public void removeObject(String objectName) {
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .build());
      log.debug("Test: Removed object from MinIO: {}", objectName);
    } catch (Exception e) {
      log.warn("Test: Failed to remove object from MinIO: {}", objectName);
    }
  }

  public boolean objectExists(String objectName) {
    try {
      minioClient.statObject(
          StatObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .build());
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
