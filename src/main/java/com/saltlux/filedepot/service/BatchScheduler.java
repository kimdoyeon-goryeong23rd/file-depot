package com.saltlux.filedepot.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.saltlux.filedepot.config.FileDepotProperties;
import com.saltlux.filedepot.config.FileDepotProperties.EmbedKitProvider;
import com.saltlux.filedepot.config.FileDepotProperties.ParsekitScenario;
import com.saltlux.filedepot.entity.ProcessingStep;
import com.saltlux.filedepot.entity.StorageItem;
import com.saltlux.filedepot.repository.StorageItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file-depot.processing.batch.enabled", havingValue = "true", matchIfMissing = false)
public class BatchScheduler {

  private final StorageItemRepository storageItemRepository;
  private final ProcessingService processingService;
  private final StorageClient storageClient;
  private final FileDepotProperties properties;

  /**
   * Retry recovery job: Reprocesses files stuck in intermediate states.
   * This handles files that failed during async processing or were interrupted.
   */
  @Scheduled(cron = "${file-depot.processing.batch.retry-cron:0 */5 * * * *}")
  public void processRetry() {
    if (!isParsingEnabled()) {
      log.debug("Parsing is disabled, skipping retry job");
      return;
    }

    log.info("Starting retry batch job");

    int batchSize = properties.getProcessing().getBatch().getBatchSize();
    int totalProcessed = 0;

    totalProcessed += retryPendingFiles(batchSize);

    if (isEmbeddingEnabled()) {
      totalProcessed += retryExtractedFiles(batchSize);
      totalProcessed += retryChunkedFiles(batchSize);
    }

    if (totalProcessed > 0) {
      log.info("Retry batch completed: {} files processed", totalProcessed);
    } else {
      log.debug("No files need retry processing");
    }
  }

  private int retryPendingFiles(int batchSize) {
    List<StorageItem> items = storageItemRepository
        .findByProcessingStepAndDeletedFalseOrderByCreatedAtAsc(ProcessingStep.PENDING, PageRequest.of(0, batchSize));

    int processed = 0;
    for (StorageItem item : items) {
      try {
        processingService.extract(item.getUuid());
        processed++;
      } catch (Exception e) {
        log.error("Retry extraction failed: uuid={}", item.getUuid(), e);
      }
    }
    return processed;
  }

  private int retryExtractedFiles(int batchSize) {
    List<StorageItem> items = storageItemRepository
        .findByProcessingStepAndDeletedFalseOrderByCreatedAtAsc(ProcessingStep.EXTRACTED, PageRequest.of(0, batchSize));

    int processed = 0;
    for (StorageItem item : items) {
      try {
        processingService.chunk(item.getUuid());
        processed++;
      } catch (Exception e) {
        log.error("Retry chunking failed: uuid={}", item.getUuid(), e);
      }
    }
    return processed;
  }

  private int retryChunkedFiles(int batchSize) {
    List<StorageItem> items = storageItemRepository
        .findByProcessingStepAndDeletedFalseOrderByCreatedAtAsc(ProcessingStep.CHUNKED, PageRequest.of(0, batchSize));

    int processed = 0;
    for (StorageItem item : items) {
      try {
        processingService.embed(item.getUuid());
        processed++;
      } catch (Exception e) {
        log.error("Retry embedding failed: uuid={}", item.getUuid(), e);
      }
    }
    return processed;
  }

  @Scheduled(cron = "${file-depot.processing.batch.orphan-cleanup-cron:0 0 * * * *}")
  public void cleanupOrphanedFiles() {
    log.debug("Starting orphan cleanup job");

    List<StorageItem> orphanedItems = storageItemRepository.findByDeletedTrue();

    if (orphanedItems.isEmpty()) {
      log.debug("No orphaned files to clean up");
      return;
    }

    log.info("Found {} orphaned files to clean up", orphanedItems.size());

    List<String> cleanedUuids = new ArrayList<>();

    for (StorageItem item : orphanedItems) {
      try {
        storageClient.removeObject(item.getUuid());
        cleanedUuids.add(item.getUuid());
        log.debug("Cleaned up orphaned file: {}", item.getUuid());
      } catch (Exception e) {
        log.warn("Failed to clean up orphaned file: uuid={}", item.getUuid(), e);
      }
    }

    if (!cleanedUuids.isEmpty()) {
      storageItemRepository.deleteByUuidIn(cleanedUuids);
      log.info("Orphan cleanup completed: {} files removed", cleanedUuids.size());
    }
  }

  public BatchStatistics getStatistics() {
    long pending = storageItemRepository.countByProcessingStepAndDeletedFalse(ProcessingStep.PENDING);
    long processing = storageItemRepository.countByProcessingStepAndDeletedFalse(ProcessingStep.PROCESSING);
    long extracted = storageItemRepository.countByProcessingStepAndDeletedFalse(ProcessingStep.EXTRACTED);
    long chunked = storageItemRepository.countByProcessingStepAndDeletedFalse(ProcessingStep.CHUNKED);
    long embedded = storageItemRepository.countByProcessingStepAndDeletedFalse(ProcessingStep.EMBEDDED);
    long failed = storageItemRepository.countByProcessingStepAndDeletedFalse(ProcessingStep.FAILED);

    return new BatchStatistics(pending, processing, extracted, chunked, embedded, failed);
  }

  private boolean isParsingEnabled() {
    ParsekitScenario scenario = properties.getParsekit().getScenario();
    return scenario == ParsekitScenario.SCENARIO1 || scenario == ParsekitScenario.SCENARIO2;
  }

  private boolean isEmbeddingEnabled() {
    EmbedKitProvider provider = properties.getEmbedkit().getProvider();
    return provider != EmbedKitProvider.NONE;
  }

  public record BatchStatistics(
      long pending,
      long processing,
      long extracted,
      long chunked,
      long embedded,
      long failed) {
    public long total() {
      return pending + processing + extracted + chunked + embedded + failed;
    }

    @Override
    public String toString() {
      return String.format("Total: %d (Pending: %d, Processing: %d, Extracted: %d, Chunked: %d, Embedded: %d, Failed: %d)",
          total(), pending, processing, extracted, chunked, embedded, failed);
    }
  }
}
