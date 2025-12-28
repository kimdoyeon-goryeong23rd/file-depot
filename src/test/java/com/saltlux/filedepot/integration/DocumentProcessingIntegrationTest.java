package com.saltlux.filedepot.integration;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.saltlux.filedepot.config.FileDepotProperties;
import com.saltlux.filedepot.config.FileDepotProperties.EmbedKitProvider;
import com.saltlux.filedepot.config.FileDepotProperties.ParsekitScenario;
import com.saltlux.filedepot.entity.Chunk;
import com.saltlux.filedepot.entity.ProcessingStep;
import com.saltlux.filedepot.entity.StorageItem;
import com.saltlux.filedepot.repository.ChunkRepository;
import com.saltlux.filedepot.repository.ExtractedContentRepository;
import com.saltlux.filedepot.repository.StorageItemRepository;
import com.saltlux.filedepot.service.ProcessingQueue;
import com.saltlux.filedepot.service.ProcessingService;
import com.saltlux.filedepot.support.TestStorageHelper;

import jakarta.persistence.EntityManager;

class DocumentProcessingIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private ProcessingQueue processingQueue;

  @Autowired
  private ProcessingService processingService;

  @Autowired
  private StorageItemRepository storageItemRepository;

  @Autowired
  private TestStorageHelper testStorageHelper;

  @Autowired
  private FileDepotProperties properties;

  @Autowired
  private ExtractedContentRepository extractedContentRepository;

  @Autowired
  private ChunkRepository chunkRepository;

  @Autowired
  private EntityManager entityManager;

  private static final Path OUTPUT_DIR = Path.of("data/outputs");
  private static final int PROCESSING_TIMEOUT_MINUTES = 1;
  private static final int POLL_INTERVAL_SECONDS = 5;

  @BeforeAll
  static void setupOutputDirectory() throws IOException {
    Files.createDirectories(OUTPUT_DIR);
  }

  private boolean isScenario1Configured() {
    return properties.getParsekit().getScenario() == ParsekitScenario.SCENARIO1;
  }

  private boolean isScenario2Configured() {
    return properties.getParsekit().getScenario() == ParsekitScenario.SCENARIO2;
  }

  private boolean isEmbeddingEnabled() {
    EmbedKitProvider provider = properties.getEmbedkit().getProvider();
    return provider != EmbedKitProvider.NONE;
  }

  @Nested
  @DisplayName("Scenario1: Converter -> Docling -> VLM")
  class Scenario1Tests {

    private static final Path INPUT_DIR = Path.of("data/inputs");

    @BeforeEach
    void checkAvailability() {
      assumeTrue(isScenario1Configured(),
          "Skipping Scenario1 tests: parsekit.scenario is not scenario1");
    }

    @TestFactory
    @DisplayName("should process documents from data/inputs")
    Stream<DynamicTest> shouldProcessDocumentsFromInputDirectory() throws IOException {
      return Files.list(INPUT_DIR)
          .filter(Files::isRegularFile)
          .filter(file -> isDocumentFile(file.getFileName().toString()))
          .map(file -> DynamicTest.dynamicTest(
              "process " + file.getFileName(),
              () -> processFile(file)));
    }

    private boolean isDocumentFile(String filename) {
      String lower = filename.toLowerCase();
      return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".hwp")
          || lower.endsWith(".hwpx") || lower.endsWith(".doc") || lower.endsWith(".pptx") || lower.endsWith(".xlsx");
    }

    private void processFile(Path file) throws IOException {
      String filename = file.getFileName().toString();
      String contentType = getContentType(filename);
      byte[] content = Files.readAllBytes(file);

      String uuid = createTestFile(filename, content, contentType);

      // Submit to queue for async processing
      processingQueue.submit(uuid);
      System.out.println(">>> [" + filename + "] Submitted to queue");

      // Determine expected final state
      ProcessingStep expectedFinalStep = isEmbeddingEnabled()
          ? ProcessingStep.EMBEDDED
          : ProcessingStep.EXTRACTED;

      // Synchronous polling until processing completes or fails
      await().atMost(PROCESSING_TIMEOUT_MINUTES, MINUTES)
          .pollInterval(POLL_INTERVAL_SECONDS, SECONDS)
          .until(() -> {
            entityManager.clear(); // Clear JPA cache to get fresh data
            StorageItem item = storageItemRepository.findByUuid(uuid).orElseThrow();
            System.out.println(">>> [" + filename + "] Current step: " + item.getProcessingStep());
            return item.getProcessingStep() == expectedFinalStep
                || item.getProcessingStep() == ProcessingStep.FAILED;
          });

      StorageItem finalItem = storageItemRepository.findByUuid(uuid).orElseThrow();
      if (finalItem.getProcessingStep() == ProcessingStep.FAILED) {
        System.out.println(">>> [" + filename + "] Processing FAILED");
      } else {
        System.out.println(">>> [" + filename + "] Processing completed: " + finalItem.getProcessingStep());
      }

      // Save extracted content and chunks to output file
      saveExtractedContent(filename, uuid);

      cleanup(uuid);
    }

    private void saveExtractedContent(String filename, String uuid) throws IOException {
      String baseName = filename.substring(0, filename.lastIndexOf('.'));

      // Save extracted text
      extractedContentRepository.findByStorageItemUuid(uuid).ifPresent(extracted -> {
        try {
          Path extractedPath = OUTPUT_DIR.resolve(baseName + "_extracted.txt");
          Files.writeString(extractedPath, extracted.getContent(), StandardCharsets.UTF_8);
          System.out.println("\n>>> [" + filename + "] Saved extracted content to: " + extractedPath);
          System.out.println(">>> Content length: " + extracted.getContent().length() + " chars");
          System.out.println(">>> Preview:\n" + extracted.getContent().substring(0, Math.min(500, extracted.getContent().length())) + "...\n");
        } catch (IOException e) {
          throw new RuntimeException("Failed to save extracted content", e);
        }
      });

      // Save chunks
      java.util.List<Chunk> chunks = chunkRepository.findByUuidOrderByChunkIndexAsc(uuid);
      if (!chunks.isEmpty()) {
        StringBuilder chunksContent = new StringBuilder();
        chunksContent.append("=== Total Chunks: ").append(chunks.size()).append(" ===\n\n");
        for (Chunk chunk : chunks) {
          chunksContent.append("--- Chunk #").append(chunk.getChunkIndex()).append(" ---\n");
          chunksContent.append(chunk.getExtractedText()).append("\n\n");
        }
        Path chunksPath = OUTPUT_DIR.resolve(baseName + "_chunks.txt");
        Files.writeString(chunksPath, chunksContent.toString(), StandardCharsets.UTF_8);
        System.out.println(">>> [" + filename + "] Saved " + chunks.size() + " chunks to: " + chunksPath);
      }
    }

    private String getContentType(String filename) {
      String lower = filename.toLowerCase();
      if (lower.endsWith(".pdf")) return "application/pdf";
      if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      if (lower.endsWith(".hwp")) return "application/x-hwp";
      if (lower.endsWith(".hwpx")) return "application/x-hwpx";
      if (lower.endsWith(".png")) return "image/png";
      if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
      return "application/octet-stream";
    }
  }

  @Nested
  @DisplayName("Scenario2: Converter -> Images -> VLM")
  class Scenario2Tests {

    private static final Path INPUT_DIR = Path.of("data/inputs");

    @BeforeEach
    void checkAvailability() {
      assumeTrue(isScenario2Configured(),
          "Skipping Scenario2 tests: parsekit.scenario is not scenario2");
    }

    @TestFactory
    @DisplayName("should process images via OCR")
    Stream<DynamicTest> shouldProcessImagesViaOcr() throws IOException {
      return Files.list(INPUT_DIR)
          .filter(Files::isRegularFile)
          .filter(file -> isImageFile(file.getFileName().toString()))
          .map(file -> DynamicTest.dynamicTest(
              "process " + file.getFileName(),
              () -> processFile(file)));
    }

    private boolean isImageFile(String filename) {
      String lower = filename.toLowerCase();
      return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
          || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".tiff");
    }

    private void processFile(Path file) throws IOException {
      String filename = file.getFileName().toString();
      String contentType = getContentType(filename);
      byte[] content = Files.readAllBytes(file);

      String uuid = createTestFile(filename, content, contentType);

      // Submit to processing queue (async)
      processingQueue.submit(uuid);
      System.out.println(">>> [" + filename + "] Submitted to queue, waiting for completion...");

      // Determine expected final state
      ProcessingStep expectedFinalStep = isEmbeddingEnabled()
          ? ProcessingStep.EMBEDDED
          : ProcessingStep.EXTRACTED;

      // Poll until processing completes or fails
      await().atMost(PROCESSING_TIMEOUT_MINUTES, MINUTES)
          .pollInterval(POLL_INTERVAL_SECONDS, SECONDS)
          .untilAsserted(() -> {
            StorageItem item = storageItemRepository.findByUuid(uuid).orElseThrow();
            System.out.println(">>> [" + filename + "] Current step: " + item.getProcessingStep());
            assertThat(item.getProcessingStep())
                .isIn(expectedFinalStep, ProcessingStep.FAILED);
          });

      StorageItem finalItem = storageItemRepository.findByUuid(uuid).orElseThrow();
      if (finalItem.getProcessingStep() == ProcessingStep.FAILED) {
        System.out.println(">>> [" + filename + "] Processing FAILED");
      } else {
        System.out.println(">>> [" + filename + "] Processing completed: " + finalItem.getProcessingStep());
      }

      // Save extracted content to output file
      saveImageExtractedContent(filename, uuid);

      cleanup(uuid);
    }

    private void saveImageExtractedContent(String filename, String uuid) throws IOException {
      String baseName = filename.substring(0, filename.lastIndexOf('.'));

      extractedContentRepository.findByStorageItemUuid(uuid).ifPresent(extracted -> {
        try {
          Path extractedPath = OUTPUT_DIR.resolve(baseName + "_ocr.txt");
          Files.writeString(extractedPath, extracted.getContent(), StandardCharsets.UTF_8);
          System.out.println("\n>>> [" + filename + "] Saved OCR result to: " + extractedPath);
          System.out.println(">>> Content length: " + extracted.getContent().length() + " chars");
          System.out.println(">>> Preview:\n" + extracted.getContent().substring(0, Math.min(500, extracted.getContent().length())) + "...\n");
        } catch (IOException e) {
          throw new RuntimeException("Failed to save OCR content", e);
        }
      });

      java.util.List<Chunk> chunks = chunkRepository.findByUuidOrderByChunkIndexAsc(uuid);
      if (!chunks.isEmpty()) {
        StringBuilder chunksContent = new StringBuilder();
        chunksContent.append("=== Total Chunks: ").append(chunks.size()).append(" ===\n\n");
        for (Chunk chunk : chunks) {
          chunksContent.append("--- Chunk #").append(chunk.getChunkIndex()).append(" ---\n");
          chunksContent.append(chunk.getExtractedText()).append("\n\n");
        }
        Path chunksPath = OUTPUT_DIR.resolve(baseName + "_chunks.txt");
        Files.writeString(chunksPath, chunksContent.toString(), StandardCharsets.UTF_8);
        System.out.println(">>> [" + filename + "] Saved " + chunks.size() + " chunks to: " + chunksPath);
      }
    }

    private String getContentType(String filename) {
      String lower = filename.toLowerCase();
      if (lower.endsWith(".png")) return "image/png";
      if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
      if (lower.endsWith(".gif")) return "image/gif";
      if (lower.endsWith(".bmp")) return "image/bmp";
      if (lower.endsWith(".tiff")) return "image/tiff";
      return "application/octet-stream";
    }
  }

  @Nested
  @DisplayName("Error handling")
  class ErrorHandlingTests {

    @BeforeEach
    void checkAvailability() {
      assumeTrue(isScenario1Configured() || isScenario2Configured(),
          "Skipping error handling tests: parsing is not enabled");
    }

    @Test
    @DisplayName("should throw exception for non-existent file")
    void shouldThrowForNonExistentFile() {
      String fakeUuid = "non-existent-uuid";

      assertThatThrownBy(() -> processingService.extract(fakeUuid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("File not found");
    }

    @Test
    @DisplayName("should mark as failed when extraction fails repeatedly")
    void shouldMarkAsFailedWhenExtractionFailsRepeatedly() {
      String uuid = java.util.UUID.randomUUID().toString();
      StorageItem item = StorageItem.builder()
          .uuid(uuid)
          .contentType("application/pdf")
          .size(100L)
          .build();
      storageItemRepository.saveAndFlush(item);

      for (int i = 0; i < 3; i++) {
        try {
          processingService.extract(uuid);
        } catch (Exception ignored) {
          // Expected: extraction fails because file doesn't exist in storage
        }
      }

      StorageItem updatedItem = storageItemRepository.findByUuid(uuid).orElseThrow();
      assertThat(updatedItem.getProcessingStep()).isEqualTo(ProcessingStep.FAILED);
    }
  }

  private String createTestFile(String filename, byte[] content, String contentType) {
    String uuid = java.util.UUID.randomUUID().toString();
    testStorageHelper.putObject(uuid, content, contentType);

    StorageItem item = StorageItem.builder()
        .uuid(uuid)
        .contentType(contentType)
        .size((long) content.length)
        .build();
    storageItemRepository.save(item);

    return uuid;
  }

  private void cleanup(String uuid) {
    testStorageHelper.removeObject(uuid);
  }

}
