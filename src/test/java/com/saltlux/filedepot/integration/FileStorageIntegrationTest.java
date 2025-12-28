package com.saltlux.filedepot.integration;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.saltlux.filedepot.config.FileDepotProperties;
import com.saltlux.filedepot.config.FileDepotProperties.EmbedKitProvider;
import com.saltlux.filedepot.config.FileDepotProperties.ParsekitScenario;
import com.saltlux.filedepot.entity.ProcessingStep;
import com.saltlux.filedepot.entity.StorageItem;
import com.saltlux.filedepot.repository.ChunkRepository;
import com.saltlux.filedepot.repository.ExtractedContentRepository;
import com.saltlux.filedepot.repository.StorageItemRepository;
import com.saltlux.filedepot.service.FileService;
import com.saltlux.filedepot.support.TestStorageHelper;

import jakarta.persistence.EntityManager;
import me.hanju.filedepot.api.dto.BatchDownloadRequest;
import me.hanju.filedepot.api.dto.ConfirmUploadRequest;
import org.springframework.transaction.support.TransactionTemplate;

class FileStorageIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private FileService fileService;

  @Autowired
  private TestStorageHelper testStorageHelper;

  @Autowired
  private StorageItemRepository storageItemRepository;

  @Autowired
  private ChunkRepository chunkRepository;

  @Autowired
  private ExtractedContentRepository extractedContentRepository;

  @Autowired
  private FileDepotProperties properties;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private static final int PROCESSING_TIMEOUT_MINUTES = 1;
  private static final int POLL_INTERVAL_SECONDS = 5;

  private void cleanupTestData(String uuid) {
    testStorageHelper.removeObject(uuid);
    transactionTemplate.executeWithoutResult(status -> {
      chunkRepository.deleteByUuid(uuid);
      extractedContentRepository.deleteByStorageItemUuid(uuid);
      storageItemRepository.deleteByUuidIn(List.of(uuid));
    });
  }

  private boolean isParsingEnabled() {
    ParsekitScenario scenario = properties.getParsekit().getScenario();
    return scenario == ParsekitScenario.SCENARIO1 || scenario == ParsekitScenario.SCENARIO2;
  }

  private boolean isEmbeddingEnabled() {
    EmbedKitProvider provider = properties.getEmbedkit().getProvider();
    return provider != EmbedKitProvider.NONE;
  }

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

      var confirmResponse = fileService.confirmUpload(new ConfirmUploadRequest(uuid, "test.txt"));

      assertThat(confirmResponse.id()).isEqualTo(uuid);
      assertThat(confirmResponse.fileName()).isEqualTo("test.txt");
      assertThat(confirmResponse.contentType()).isEqualTo("text/plain");
      assertThat(confirmResponse.size()).isEqualTo(12L);

      testStorageHelper.removeObject(uuid);
      storageItemRepository.deleteByUuidIn(List.of(uuid));
    }

    @Test
    @DisplayName("should throw exception when confirming non-uploaded file")
    void shouldThrowWhenConfirmingNonUploadedFile() {
      assertThatThrownBy(() -> fileService.confirmUpload(new ConfirmUploadRequest("non-existent-uuid", "test.txt")))
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
      fileService.confirmUpload(new ConfirmUploadRequest(uuid, "test.json"));

      var metadata = fileService.getFileMetadata(uuid, false);

      assertThat(metadata.id()).isEqualTo(uuid);
      assertThat(metadata.fileName()).isEqualTo("test.json");
      assertThat(metadata.contentType()).isEqualTo("application/json");
      assertThat(metadata.content()).isNull();

      testStorageHelper.removeObject(uuid);
      storageItemRepository.deleteByUuidIn(List.of(uuid));
    }

    @Test
    @DisplayName("should throw when getting metadata for non-existent file")
    void shouldThrowForNonExistentFile() {
      assertThatThrownBy(() -> fileService.getFileMetadata("non-existent", false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("File not found");
    }

    @Test
    @DisplayName("should get download URL")
    void shouldGetDownloadUrl() {
      var prepareResponse = fileService.prepareUpload();
      String uuid = prepareResponse.id();
      testStorageHelper.putObject(uuid, "download test".getBytes(), "text/plain");
      fileService.confirmUpload(new ConfirmUploadRequest(uuid, "download.txt"));

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
      fileService.confirmUpload(new ConfirmUploadRequest(response1.id(), "file1.txt"));
      fileService.confirmUpload(new ConfirmUploadRequest(response2.id(), "file2.txt"));

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

  @Nested
  @DisplayName("File content retrieval")
  class FileContentRetrievalTests {

    @BeforeEach
    void checkAvailability() {
      assumeTrue(isParsingEnabled(),
          "Skipping content retrieval tests: parsing is not enabled");
    }

    @Test
    @DisplayName("should get file metadata with extracted content when withContent=true")
    void shouldGetFileMetadataWithContent() {
      var prepareResponse = fileService.prepareUpload();
      String uuid = prepareResponse.id();
      testStorageHelper.putObject(uuid, "This is test content for extraction".getBytes(), "text/plain");
      fileService.confirmUpload(new ConfirmUploadRequest(uuid, "content-test.txt"));

      ProcessingStep expectedFinalStep = isEmbeddingEnabled()
          ? ProcessingStep.EMBEDDED
          : ProcessingStep.EXTRACTED;

      await().atMost(PROCESSING_TIMEOUT_MINUTES, MINUTES)
          .pollInterval(POLL_INTERVAL_SECONDS, SECONDS)
          .until(() -> {
            entityManager.clear();
            StorageItem item = storageItemRepository.findByUuid(uuid).orElseThrow();
            return item.getProcessingStep() == expectedFinalStep
                || item.getProcessingStep() == ProcessingStep.FAILED;
          });

      var metadataWithContent = fileService.getFileMetadata(uuid, true);
      var metadataWithoutContent = fileService.getFileMetadata(uuid, false);

      assertThat(metadataWithContent.id()).isEqualTo(uuid);
      assertThat(metadataWithContent.content()).isNotNull();
      assertThat(metadataWithContent.content()).isNotBlank();

      assertThat(metadataWithoutContent.id()).isEqualTo(uuid);
      assertThat(metadataWithoutContent.content()).isNull();

      cleanupTestData(uuid);
    }
  }

  @Nested
  @DisplayName("Chunks retrieval")
  class ChunksRetrievalTests {

    @BeforeEach
    void checkAvailability() {
      assumeTrue(isParsingEnabled() && isEmbeddingEnabled(),
          "Skipping chunks retrieval tests: parsing or embedding is not enabled");
    }

    @Test
    @DisplayName("should get chunks without embedding when withEmbedding=false")
    void shouldGetChunksWithoutEmbedding() {
      var prepareResponse = fileService.prepareUpload();
      String uuid = prepareResponse.id();
      testStorageHelper.putObject(uuid, "Test content for chunking without embedding".getBytes(), "text/plain");
      fileService.confirmUpload(new ConfirmUploadRequest(uuid, "chunks-test.txt"));

      await().atMost(PROCESSING_TIMEOUT_MINUTES, MINUTES)
          .pollInterval(POLL_INTERVAL_SECONDS, SECONDS)
          .until(() -> {
            entityManager.clear();
            StorageItem item = storageItemRepository.findByUuid(uuid).orElseThrow();
            return item.getProcessingStep() == ProcessingStep.EMBEDDED
                || item.getProcessingStep() == ProcessingStep.FAILED;
          });

      var chunks = fileService.getChunks(uuid, false);

      assertThat(chunks).isNotEmpty();
      assertThat(chunks.get(0).content()).isNotNull();
      assertThat(chunks.get(0).embedding()).isNull();

      cleanupTestData(uuid);
    }

    @Test
    @DisplayName("should get chunks with embedding when withEmbedding=true")
    void shouldGetChunksWithEmbedding() {
      var prepareResponse = fileService.prepareUpload();
      String uuid = prepareResponse.id();
      testStorageHelper.putObject(uuid, "Test content for chunking with embedding".getBytes(), "text/plain");
      fileService.confirmUpload(new ConfirmUploadRequest(uuid, "chunks-embed-test.txt"));

      await().atMost(PROCESSING_TIMEOUT_MINUTES, MINUTES)
          .pollInterval(POLL_INTERVAL_SECONDS, SECONDS)
          .until(() -> {
            entityManager.clear();
            StorageItem item = storageItemRepository.findByUuid(uuid).orElseThrow();
            return item.getProcessingStep() == ProcessingStep.EMBEDDED
                || item.getProcessingStep() == ProcessingStep.FAILED;
          });

      var chunks = fileService.getChunks(uuid, true);

      assertThat(chunks).isNotEmpty();
      assertThat(chunks.get(0).content()).isNotNull();
      assertThat(chunks.get(0).embedding()).isNotNull();
      assertThat(chunks.get(0).embedding()).isNotEmpty();

      cleanupTestData(uuid);
    }

    @Test
    @DisplayName("should throw when getting chunks for non-existent file")
    void shouldThrowForNonExistentFileChunks() {
      assertThatThrownBy(() -> fileService.getChunks("non-existent", false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("File not found");
    }
  }

  @Nested
  @DisplayName("Batch download")
  class BatchDownloadTests {

    @Test
    @DisplayName("should create ZIP with multiple files from MinIO")
    void shouldCreateZipWithMultipleFiles() throws Exception {
      // Given: 3개 파일을 MinIO에 업로드
      var file1 = createTestFile("file1.txt", "Content of file 1", "text/plain");
      var file2 = createTestFile("file2.txt", "Content of file 2", "text/plain");
      var file3 = createTestFile("document.pdf", "PDF content here", "application/pdf");

      List<String> uuids = List.of(file1, file2, file3);

      // When: 배치 다운로드 요청
      var streamingBody = fileService.downloadBatch(new BatchDownloadRequest(uuids));

      // Then: ZIP 파일 검증
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      streamingBody.writeTo(baos);

      try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
        Map<String, String> entries = new HashMap<>();

        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
          String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
          entries.put(entry.getName(), content);
          zis.closeEntry();
        }

        assertThat(entries).hasSize(3);
        assertThat(entries.get("file1.txt")).isEqualTo("Content of file 1");
        assertThat(entries.get("file2.txt")).isEqualTo("Content of file 2");
        assertThat(entries.get("document.pdf")).isEqualTo("PDF content here");
      }

      // Cleanup
      uuids.forEach(uuid -> {
        testStorageHelper.removeObject(uuid);
        storageItemRepository.deleteByUuidIn(List.of(uuid));
      });
    }

    @Test
    @DisplayName("should handle duplicate file names by appending suffix")
    void shouldHandleDuplicateFileNames() throws Exception {
      // Given: 동일한 파일명으로 3개 파일 업로드
      var file1 = createTestFile("report.txt", "First report", "text/plain");
      var file2 = createTestFile("report.txt", "Second report", "text/plain");
      var file3 = createTestFile("report.txt", "Third report", "text/plain");

      List<String> uuids = List.of(file1, file2, file3);

      // When
      var streamingBody = fileService.downloadBatch(new BatchDownloadRequest(uuids));

      // Then: ZIP 내 파일명 중복 해결 확인
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      streamingBody.writeTo(baos);

      try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
        List<String> entryNames = new ArrayList<>();

        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
          entryNames.add(entry.getName());
          zis.closeEntry();
        }

        assertThat(entryNames).hasSize(3);
        assertThat(entryNames).containsExactlyInAnyOrder("report.txt", "report (1).txt", "report (2).txt");
      }

      // Cleanup
      uuids.forEach(uuid -> {
        testStorageHelper.removeObject(uuid);
        storageItemRepository.deleteByUuidIn(List.of(uuid));
      });
    }

    @Test
    @DisplayName("should throw when ID list is empty")
    void shouldThrowWhenIdListIsEmpty() {
      assertThatThrownBy(() -> fileService.downloadBatch(new BatchDownloadRequest(List.of())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("should throw when no files found for given UUIDs")
    void shouldThrowWhenNoFilesFound() {
      assertThatThrownBy(() -> fileService.downloadBatch(new BatchDownloadRequest(List.of("non-existent-uuid"))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("No files found");
    }

    private String createTestFile(String fileName, String content, String contentType) {
      var prepareResponse = fileService.prepareUpload();
      String uuid = prepareResponse.id();
      testStorageHelper.putObject(uuid, content.getBytes(StandardCharsets.UTF_8), contentType);
      fileService.confirmUpload(new ConfirmUploadRequest(uuid, fileName));
      return uuid;
    }
  }
}
