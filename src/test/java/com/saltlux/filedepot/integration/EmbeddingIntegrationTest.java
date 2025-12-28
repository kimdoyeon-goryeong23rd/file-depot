package com.saltlux.filedepot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.saltlux.filedepot.config.FileDepotProperties;
import com.saltlux.filedepot.config.FileDepotProperties.EmbedKitProvider;
import com.saltlux.filedepot.entity.Chunk;
import com.saltlux.filedepot.entity.ExtractedContent;
import com.saltlux.filedepot.entity.ProcessingStep;
import com.saltlux.filedepot.entity.StorageItem;
import com.saltlux.filedepot.repository.ChunkRepository;
import com.saltlux.filedepot.repository.ExtractedContentRepository;
import com.saltlux.filedepot.repository.StorageItemRepository;
import com.saltlux.filedepot.service.ProcessingService;

@Transactional
class EmbeddingIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private ProcessingService processingService;

  @Autowired
  private ChunkRepository chunkRepository;

  @Autowired
  private ExtractedContentRepository extractedContentRepository;

  @Autowired
  private StorageItemRepository storageItemRepository;

  @Autowired
  private FileDepotProperties properties;

  private boolean isVllmConfigured() {
    return properties.getEmbedkit().getProvider() == EmbedKitProvider.VLLM;
  }

  private boolean isLuxiaConfigured() {
    return properties.getEmbedkit().getProvider() == EmbedKitProvider.LUXIA;
  }

  private boolean isEmbeddingEnabled() {
    return properties.getEmbedkit().getProvider() != EmbedKitProvider.NONE;
  }

  private StorageItem createStorageItem(String uuid, ProcessingStep step) {
    StorageItem item = StorageItem.builder()
        .uuid(uuid)
        .contentType("text/plain")
        .size(100L)
        .processingStep(step)
        .build();
    return storageItemRepository.saveAndFlush(item);
  }

  private void createExtractedContent(String uuid, String text) {
    StorageItem item = storageItemRepository.findByUuid(uuid).orElseThrow();
    ExtractedContent content = new ExtractedContent(item, text);
    extractedContentRepository.saveAndFlush(content);
  }

  private void cleanup(String uuid) {
    chunkRepository.deleteByUuid(uuid);
    extractedContentRepository.deleteByStorageItemUuid(uuid);
    storageItemRepository.findByUuid(uuid).ifPresent(storageItemRepository::delete);
  }

  @Nested
  @DisplayName("VLLM Embedding")
  class VllmEmbeddingTests {

    @BeforeEach
    void checkAvailability() {
      assumeTrue(isVllmConfigured(),
          "Skipping VLLM tests: embedkit.provider is not vllm");
    }

    @Test
    @DisplayName("should chunk and generate embeddings with VLLM")
    void shouldChunkAndGenerateEmbeddingsWithVllm() {
      String uuid = java.util.UUID.randomUUID().toString();

      String sampleText = "This is a sample text for embedding generation. " +
          "It contains multiple sentences to test chunking functionality. " +
          "The embedding model should process this text and return vector representations.";

      createStorageItem(uuid, ProcessingStep.EXTRACTED);
      createExtractedContent(uuid, sampleText);

      processingService.chunk(uuid);
      processingService.embed(uuid);

      List<Chunk> chunks = chunkRepository.findByUuidOrderByChunkIndexAsc(uuid);
      assertThat(chunks).isNotEmpty();
      assertThat(chunks.get(0).getEmbedding()).isNotNull();
      assertThat(chunks.get(0).getEmbedding().length).isGreaterThan(0);

      StorageItem updatedItem = storageItemRepository.findByUuid(uuid).orElseThrow();
      assertThat(updatedItem.getProcessingStep()).isEqualTo(ProcessingStep.EMBEDDED);

      cleanup(uuid);
    }

    @Test
    @DisplayName("should chunk long text and generate embeddings for each chunk")
    void shouldChunkLongTextAndGenerateEmbeddings() {
      String uuid = java.util.UUID.randomUUID().toString();

      StringBuilder longText = new StringBuilder();
      for (int i = 0; i < 100; i++) {
        longText.append("This is paragraph ").append(i).append(". ");
        longText.append("It contains some content that needs to be processed. ");
        longText.append("The chunking algorithm should split this into multiple parts. ");
      }

      createStorageItem(uuid, ProcessingStep.EXTRACTED);
      createExtractedContent(uuid, longText.toString());

      processingService.chunk(uuid);
      processingService.embed(uuid);

      List<Chunk> chunks = chunkRepository.findByUuidOrderByChunkIndexAsc(uuid);
      assertThat(chunks).hasSizeGreaterThan(1);

      for (int i = 0; i < chunks.size(); i++) {
        Chunk chunk = chunks.get(i);
        assertThat(chunk.getChunkIndex()).isEqualTo(i);
        assertThat(chunk.getExtractedText()).isNotBlank();
        assertThat(chunk.getEmbedding()).isNotNull();
      }

      cleanup(uuid);
    }
  }

  @Nested
  @DisplayName("Luxia Embedding")
  class LuxiaEmbeddingTests {

    @BeforeEach
    void checkAvailability() {
      assumeTrue(isLuxiaConfigured(),
          "Skipping Luxia tests: embedkit.provider is not luxia");
    }

    @Test
    @DisplayName("should chunk and generate embeddings with Luxia")
    void shouldChunkAndGenerateEmbeddingsWithLuxia() {
      String uuid = java.util.UUID.randomUUID().toString();

      String sampleText = "This is a sample text for Luxia embedding generation. " +
          "It should work similarly to VLLM embeddings.";

      createStorageItem(uuid, ProcessingStep.EXTRACTED);
      createExtractedContent(uuid, sampleText);

      processingService.chunk(uuid);
      processingService.embed(uuid);

      List<Chunk> chunks = chunkRepository.findByUuidOrderByChunkIndexAsc(uuid);
      assertThat(chunks).isNotEmpty();
      assertThat(chunks.get(0).getEmbedding()).isNotNull();
      assertThat(chunks.get(0).getEmbedding().length).isGreaterThan(0);

      cleanup(uuid);
    }
  }

  @Nested
  @DisplayName("Chunking only (no embedding)")
  class ChunkingOnlyTests {

    @BeforeEach
    void checkAvailability() {
      assumeTrue(isEmbeddingEnabled(),
          "Skipping chunking tests: embedding is not enabled");
    }

    @Test
    @DisplayName("should chunk text and update step to CHUNKED")
    void shouldChunkTextAndUpdateStep() {
      String uuid = java.util.UUID.randomUUID().toString();

      String sampleText = "This is sample text that should be chunked.";

      createStorageItem(uuid, ProcessingStep.EXTRACTED);
      createExtractedContent(uuid, sampleText);

      processingService.chunk(uuid);

      List<Chunk> chunks = chunkRepository.findByUuidOrderByChunkIndexAsc(uuid);
      assertThat(chunks).isNotEmpty();
      assertThat(chunks.get(0).getExtractedText()).isNotBlank();
      assertThat(chunks.get(0).getEmbedding()).isNull();

      StorageItem updatedItem = storageItemRepository.findByUuid(uuid).orElseThrow();
      assertThat(updatedItem.getProcessingStep()).isEqualTo(ProcessingStep.CHUNKED);

      cleanup(uuid);
    }
  }
}
