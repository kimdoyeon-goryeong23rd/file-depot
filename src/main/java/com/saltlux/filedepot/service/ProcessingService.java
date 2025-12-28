package com.saltlux.filedepot.service;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.saltlux.embedkit.TextEmbeddingClient;
import com.saltlux.embedkit.payload.ChunkResult;
import com.saltlux.embedkit.payload.EmbeddingResult;
import com.saltlux.filedepot.config.FileDepotProperties;
import com.saltlux.filedepot.config.FileDepotProperties.EmbedKitProvider;
import com.saltlux.filedepot.config.FileDepotProperties.ParsekitScenario;
import com.saltlux.filedepot.entity.Chunk;
import com.saltlux.filedepot.entity.ExtractedContent;
import com.saltlux.filedepot.entity.ProcessingStep;
import com.saltlux.filedepot.entity.StorageItem;
import com.saltlux.filedepot.repository.ChunkRepository;
import com.saltlux.filedepot.repository.ExtractedContentRepository;
import com.saltlux.filedepot.repository.StorageItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingService {

  private final StorageItemRepository storageItemRepository;
  private final ExtractedContentRepository extractedContentRepository;
  private final ChunkRepository chunkRepository;
  private final TextExtractor textExtractor;
  private final FileDepotProperties properties;
  private final TransactionTemplate transactionTemplate;

  @Autowired(required = false)
  private TextEmbeddingClient textEmbeddingClient;

  public void extract(String uuid) {
    if (!isParsingEnabled()) {
      throw new IllegalStateException("Parsing is not enabled");
    }

    log.info("Starting extraction for file: {}", uuid);

    StorageItem item = transactionTemplate.execute(status -> {
      StorageItem found = storageItemRepository.findByUuidAndDeletedFalse(uuid)
          .orElseThrow(() -> new IllegalArgumentException("File not found: " + uuid));
      found.updateStep(ProcessingStep.PROCESSING);
      return storageItemRepository.save(found);
    });

    ProcessingStep previousStep = ProcessingStep.PENDING;

    try {
      String extractedText = textExtractor.extract(uuid, item.getContentType());

      if (extractedText == null || extractedText.isEmpty()) {
        log.warn("No text extracted from file: {}", uuid);
        transactionTemplate.executeWithoutResult(status -> {
          item.updateStep(previousStep);
          storageItemRepository.save(item);
        });
        return;
      }

      transactionTemplate.executeWithoutResult(status -> {
        saveExtractedContent(uuid, extractedText);
        StorageItem current = storageItemRepository.findByUuid(uuid).orElseThrow();
        current.updateStep(ProcessingStep.EXTRACTED);
        current.resetRetryCount();
        storageItemRepository.save(current);
      });
      log.info("Extraction completed for file: {}", uuid);

    } catch (Exception e) {
      log.error("Extraction failed for file: {}", uuid, e);
      handleFailure(uuid, previousStep);
      throw e;
    }
  }

  public void chunk(String uuid) {
    if (!isEmbeddingEnabled()) {
      throw new IllegalStateException("Embedding is not enabled");
    }

    log.info("Starting chunking for file: {}", uuid);

    StorageItem item = transactionTemplate.execute(status -> {
      StorageItem found = storageItemRepository.findByUuidAndDeletedFalse(uuid)
          .orElseThrow(() -> new IllegalArgumentException("File not found: " + uuid));
      found.updateStep(ProcessingStep.PROCESSING);
      return storageItemRepository.save(found);
    });

    ProcessingStep previousStep = ProcessingStep.EXTRACTED;

    try {
      String content = transactionTemplate.execute(status -> {
        ExtractedContent extractedContent = extractedContentRepository.findByStorageItemUuid(uuid)
            .orElseThrow(() -> new IllegalStateException("Extracted content not found: " + uuid));
        return extractedContent.getContent();
      });

      List<String> chunks = chunkText(content);

      transactionTemplate.executeWithoutResult(status -> {
        chunkRepository.deleteByUuid(uuid);

        for (int i = 0; i < chunks.size(); i++) {
          Chunk chunk = Chunk.builder()
              .uuid(uuid)
              .chunkIndex(i)
              .extractedText(chunks.get(i))
              .build();
          chunkRepository.save(chunk);
        }

        item.updateStep(ProcessingStep.CHUNKED);
        item.resetRetryCount();
        storageItemRepository.save(item);
      });
      log.info("Chunking completed for file: {} ({} chunks)", uuid, chunks.size());

    } catch (Exception e) {
      log.error("Chunking failed for file: {}", uuid, e);
      handleFailure(uuid, previousStep);
      throw e;
    }
  }

  public void embed(String uuid) {
    if (!isEmbeddingEnabled()) {
      throw new IllegalStateException("Embedding is not enabled");
    }

    log.info("Starting embedding for file: {}", uuid);

    StorageItem item = transactionTemplate.execute(status -> {
      StorageItem found = storageItemRepository.findByUuidAndDeletedFalse(uuid)
          .orElseThrow(() -> new IllegalArgumentException("File not found: " + uuid));
      found.updateStep(ProcessingStep.PROCESSING);
      return storageItemRepository.save(found);
    });

    ProcessingStep previousStep = ProcessingStep.CHUNKED;

    try {
      List<Chunk> contents = transactionTemplate.execute(status ->
          chunkRepository.findByUuidOrderByChunkIndexAsc(uuid));

      if (contents.isEmpty()) {
        log.warn("No content found for embedding generation: {}", uuid);
        transactionTemplate.executeWithoutResult(status -> {
          item.updateStep(previousStep);
          storageItemRepository.save(item);
        });
        return;
      }

      List<String> texts = contents.stream()
          .map(Chunk::getExtractedText)
          .toList();

      List<EmbeddingResult> results = textEmbeddingClient.embed(texts);

      transactionTemplate.executeWithoutResult(status -> {
        for (EmbeddingResult result : results) {
          Chunk content = contents.get(result.index());
          content.updateEmbedding(toBytes(result.embedding()));
          chunkRepository.save(content);
        }

        item.updateStep(ProcessingStep.EMBEDDED);
        item.resetRetryCount();
        storageItemRepository.save(item);
      });
      log.info("Embedding completed for file: {} ({} chunks)", uuid, results.size());

    } catch (Exception e) {
      log.error("Embedding failed for file: {}", uuid, e);
      handleFailure(uuid, previousStep);
      throw e;
    }
  }

  private void handleFailure(String uuid, ProcessingStep previousStep) {
    transactionTemplate.executeWithoutResult(status -> {
      StorageItem item = storageItemRepository.findByUuid(uuid).orElseThrow();
      int maxRetryCount = properties.getProcessing().getMaxRetryCount();
      item.incrementRetryCount();
      if (item.getRetryCount() >= maxRetryCount) {
        item.updateStep(ProcessingStep.FAILED);
        log.warn("Max retry count reached for file: {}, marking as FAILED", uuid);
      } else {
        item.updateStep(previousStep);
        log.info("Retry count for file {}: {}/{}", uuid, item.getRetryCount(), maxRetryCount);
      }
      storageItemRepository.save(item);
    });
  }

  private void saveExtractedContent(String uuid, String text) {
    extractedContentRepository.deleteByStorageItemUuid(uuid);

    StorageItem item = storageItemRepository.findByUuid(uuid)
        .orElseThrow(() -> new IllegalStateException("StorageItem not found: " + uuid));
    ExtractedContent content = new ExtractedContent(item, text);
    extractedContentRepository.save(content);

    log.info("Saved extracted content for file: {} ({} chars)", uuid, text.length());
  }

  private List<String> chunkText(String text) {
    int chunkSize = properties.getProcessing().getChunking().getSize();
    int chunkOverlap = properties.getProcessing().getChunking().getOverlap();
    ChunkResult result = textEmbeddingClient.chunk(text, chunkSize, chunkOverlap);
    List<String> chunks = result.chunks();

    if (chunks == null || chunks.isEmpty()) {
      return List.of(text);
    }

    return new ArrayList<>(chunks);
  }

  private boolean isParsingEnabled() {
    ParsekitScenario scenario = properties.getParsekit().getScenario();
    return scenario == ParsekitScenario.SCENARIO1 || scenario == ParsekitScenario.SCENARIO2;
  }

  private boolean isEmbeddingEnabled() {
    EmbedKitProvider provider = properties.getEmbedkit().getProvider();
    return provider != EmbedKitProvider.NONE && textEmbeddingClient != null;
  }

  private byte[] toBytes(List<Float> embedding) {
    if (embedding == null || embedding.isEmpty()) {
      return null;
    }
    ByteBuffer buffer = ByteBuffer.allocate(embedding.size() * 4);
    for (Float f : embedding) {
      buffer.putFloat(f);
    }
    return buffer.array();
  }
}
