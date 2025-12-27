package com.saltlux.filedepot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.saltlux.filedepot.repository.StorageItemRepository;
import com.saltlux.filedepot.service.FileService;
import com.saltlux.filedepot.support.TestStorageHelper;

import me.hanju.filedepot.api.dto.ConfirmUploadRequest;

class FileStorageIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private FileService fileService;

  @Autowired
  private TestStorageHelper testStorageHelper;

  @Autowired
  private StorageItemRepository storageItemRepository;

  @Nested
  @DisplayName("Presigned upload flow")
  class PresignedUploadFlowTests {

    @Test
    @DisplayName("should prepare upload and return presigned URL")
    void shouldPrepareUpload() {
      var response = fileService.prepareUpload();

      assertThat(response.id()).isNotBlank();
      assertThat(response.uploadUrl()).isNotBlank();
      assertThat(response.expirySeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("should confirm upload after file is uploaded to MinIO")
    void shouldConfirmUpload() {
      var prepareResponse = fileService.prepareUpload();
      String uuid = prepareResponse.id();

      testStorageHelper.putObject(uuid, "test content".getBytes(), "text/plain");

      var confirmResponse = fileService.confirmUpload(new ConfirmUploadRequest(uuid));

      assertThat(confirmResponse.id()).isEqualTo(uuid);
      assertThat(confirmResponse.contentType()).isEqualTo("text/plain");
      assertThat(confirmResponse.size()).isEqualTo(12L);

      testStorageHelper.removeObject(uuid);
      storageItemRepository.deleteByUuidIn(List.of(uuid));
    }

    @Test
    @DisplayName("should throw exception when confirming non-uploaded file")
    void shouldThrowWhenConfirmingNonUploadedFile() {
      assertThatThrownBy(() -> fileService.confirmUpload(new ConfirmUploadRequest("non-existent-uuid")))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Nested
  @DisplayName("File metadata and download")
  class FileMetadataDownloadTests {

    @Test
    @DisplayName("should get file metadata")
    void shouldGetFileMetadata() {
      var prepareResponse = fileService.prepareUpload();
      String uuid = prepareResponse.id();
      testStorageHelper.putObject(uuid, "metadata test".getBytes(), "application/json");
      fileService.confirmUpload(new ConfirmUploadRequest(uuid));

      var metadata = fileService.getFileMetadata(uuid);

      assertThat(metadata.id()).isEqualTo(uuid);
      assertThat(metadata.contentType()).isEqualTo("application/json");

      testStorageHelper.removeObject(uuid);
      storageItemRepository.deleteByUuidIn(List.of(uuid));
    }

    @Test
    @DisplayName("should throw when getting metadata for non-existent file")
    void shouldThrowForNonExistentFile() {
      assertThatThrownBy(() -> fileService.getFileMetadata("non-existent"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("File not found");
    }

    @Test
    @DisplayName("should get download URL")
    void shouldGetDownloadUrl() {
      var prepareResponse = fileService.prepareUpload();
      String uuid = prepareResponse.id();
      testStorageHelper.putObject(uuid, "download test".getBytes(), "text/plain");
      fileService.confirmUpload(new ConfirmUploadRequest(uuid));

      var downloadResponse = fileService.getDownloadUrl(uuid);

      assertThat(downloadResponse.downloadUrl()).isNotBlank();
      assertThat(downloadResponse.downloadUrl()).contains(uuid);
      assertThat(downloadResponse.expirySeconds()).isEqualTo(3600);

      testStorageHelper.removeObject(uuid);
      storageItemRepository.deleteByUuidIn(List.of(uuid));
    }
  }

  @Nested
  @DisplayName("File deletion")
  class FileDeletionTests {

    @Test
    @DisplayName("should delete files from MinIO and database")
    void shouldDeleteFiles() {
      var response1 = fileService.prepareUpload();
      var response2 = fileService.prepareUpload();
      testStorageHelper.putObject(response1.id(), "file1".getBytes(), "text/plain");
      testStorageHelper.putObject(response2.id(), "file2".getBytes(), "text/plain");
      fileService.confirmUpload(new ConfirmUploadRequest(response1.id()));
      fileService.confirmUpload(new ConfirmUploadRequest(response2.id()));

      List<String> uuids = List.of(response1.id(), response2.id());

      fileService.deleteFiles(uuids);

      assertThat(testStorageHelper.objectExists(response1.id())).isFalse();
      assertThat(testStorageHelper.objectExists(response2.id())).isFalse();
      assertThat(storageItemRepository.findByUuid(response1.id())).isEmpty();
      assertThat(storageItemRepository.findByUuid(response2.id())).isEmpty();
    }

    @Test
    @DisplayName("should handle null list gracefully")
    void shouldHandleNullList() {
      fileService.deleteFiles(null);
    }

    @Test
    @DisplayName("should handle empty list gracefully")
    void shouldHandleEmptyList() {
      fileService.deleteFiles(List.of());
    }
  }
}
