package com.saltlux.filedepot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.saltlux.filedepot.config.FileDepotProperties;
import com.saltlux.filedepot.config.FileDepotProperties.ParsekitScenario;
import com.saltlux.filedepot.entity.ProcessingStep;
import com.saltlux.filedepot.entity.StorageItem;
import com.saltlux.filedepot.repository.StorageItemRepository;
import com.saltlux.filedepot.service.ProcessingService;
import com.saltlux.filedepot.support.TestStorageHelper;

class DocumentProcessingIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private ProcessingService processingService;

  @Autowired
  private StorageItemRepository storageItemRepository;

  @Autowired
  private TestStorageHelper testStorageHelper;

  @Autowired
  private FileDepotProperties properties;

  private boolean isScenario1Configured() {
    return properties.getParsekit().getScenario() == ParsekitScenario.SCENARIO1;
  }

  private boolean isScenario2Configured() {
    return properties.getParsekit().getScenario() == ParsekitScenario.SCENARIO2;
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
          || lower.endsWith(".doc") || lower.endsWith(".pptx") || lower.endsWith(".xlsx");
    }

    private void processFile(Path file) throws IOException {
      String filename = file.getFileName().toString();
      String contentType = getContentType(filename);
      byte[] content = Files.readAllBytes(file);

      String uuid = createTestFile(filename, content, contentType);

      processingService.extract(uuid);

      StorageItem item = storageItemRepository.findByUuid(uuid).orElseThrow();
      assertThat(item.getProcessingStep())
          .isIn(ProcessingStep.EXTRACTED, ProcessingStep.FAILED);

      cleanup(uuid);
    }

    private String getContentType(String filename) {
      String lower = filename.toLowerCase();
      if (lower.endsWith(".pdf")) return "application/pdf";
      if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      if (lower.endsWith(".hwp")) return "application/x-hwp";
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

      processingService.extract(uuid);

      StorageItem item = storageItemRepository.findByUuid(uuid).orElseThrow();
      assertThat(item.getProcessingStep())
          .isIn(ProcessingStep.EXTRACTED, ProcessingStep.FAILED);

      cleanup(uuid);
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
