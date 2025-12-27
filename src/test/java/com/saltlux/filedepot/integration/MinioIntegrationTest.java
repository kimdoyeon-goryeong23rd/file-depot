package com.saltlux.filedepot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.saltlux.filedepot.service.StorageClient;
import com.saltlux.filedepot.support.TestStorageHelper;

class MinioIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private StorageClient storageClient;

  @Autowired
  private TestStorageHelper testStorageHelper;

  @Nested
  @DisplayName("Object upload and download")
  class ObjectUploadDownloadTests {

    @Test
    @DisplayName("should upload and download content successfully")
    void shouldUploadAndDownloadContent() throws Exception {
      String objectName = "test-object-" + System.currentTimeMillis();
      byte[] content = "Hello, MinIO!".getBytes(StandardCharsets.UTF_8);

      testStorageHelper.putObject(objectName, content, "text/plain");

      try (InputStream is = storageClient.getObject(objectName)) {
        byte[] downloaded = is.readAllBytes();
        assertThat(new String(downloaded, StandardCharsets.UTF_8)).isEqualTo("Hello, MinIO!");
      }

      testStorageHelper.removeObject(objectName);
    }

    @Test
    @DisplayName("should upload binary content")
    void shouldUploadBinaryContent() throws Exception {
      String objectName = "binary-" + System.currentTimeMillis();
      byte[] content = new byte[] { 0x00, 0x01, 0x02, (byte) 0xFF };

      testStorageHelper.putObject(objectName, content, "application/octet-stream");

      byte[] downloaded = storageClient.getObjectBytes(objectName);
      assertThat(downloaded).isEqualTo(content);

      testStorageHelper.removeObject(objectName);
    }
  }

  @Nested
  @DisplayName("Object existence check")
  class ObjectExistsTests {

    @Test
    @DisplayName("should return true for existing object")
    void shouldReturnTrueForExistingObject() {
      String objectName = "exists-test-" + System.currentTimeMillis();
      testStorageHelper.putObject(objectName, "test".getBytes(), "text/plain");

      assertThat(testStorageHelper.objectExists(objectName)).isTrue();

      testStorageHelper.removeObject(objectName);
    }

    @Test
    @DisplayName("should return false for non-existing object")
    void shouldReturnFalseForNonExistingObject() {
      assertThat(testStorageHelper.objectExists("non-existent-object")).isFalse();
    }
  }

  @Nested
  @DisplayName("Object removal")
  class ObjectRemovalTests {

    @Test
    @DisplayName("should remove object successfully")
    void shouldRemoveObject() {
      String objectName = "remove-test-" + System.currentTimeMillis();
      testStorageHelper.putObject(objectName, "test".getBytes(), "text/plain");

      assertThat(testStorageHelper.objectExists(objectName)).isTrue();

      storageClient.removeObject(objectName);

      assertThat(testStorageHelper.objectExists(objectName)).isFalse();
    }
  }

  @Nested
  @DisplayName("Presigned URLs")
  class PresignedUrlTests {

    @Test
    @DisplayName("should generate presigned upload URL")
    void shouldGeneratePresignedUploadUrl() {
      String objectName = "presigned-upload-" + System.currentTimeMillis();

      String url = storageClient.getPresignedUploadUrl(objectName, 3600);

      assertThat(url).isNotBlank();
      assertThat(url).contains(objectName);
    }

    @Test
    @DisplayName("should generate presigned download URL for existing object")
    void shouldGeneratePresignedDownloadUrl() {
      String objectName = "presigned-download-" + System.currentTimeMillis();
      testStorageHelper.putObject(objectName, "test".getBytes(), "text/plain");

      String url = storageClient.getPresignedDownloadUrl(objectName, 3600);

      assertThat(url).isNotBlank();
      assertThat(url).contains(objectName);

      testStorageHelper.removeObject(objectName);
    }
  }

  @Nested
  @DisplayName("Stat object")
  class StatObjectTests {

    @Test
    @DisplayName("should return object stats")
    void shouldReturnObjectStats() {
      String objectName = "stat-test-" + System.currentTimeMillis();
      byte[] content = "test content".getBytes();
      testStorageHelper.putObject(objectName, content, "text/plain");

      var stat = storageClient.statObject(objectName);

      assertThat(stat.size()).isEqualTo(content.length);
      assertThat(stat.contentType()).isEqualTo("text/plain");

      testStorageHelper.removeObject(objectName);
    }

    @Test
    @DisplayName("should throw exception for non-existing object")
    void shouldThrowForNonExistingObject() {
      assertThatThrownBy(() -> storageClient.statObject("non-existent"))
          .isInstanceOf(RuntimeException.class);
    }
  }
}
