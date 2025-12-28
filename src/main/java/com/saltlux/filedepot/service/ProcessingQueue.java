package com.saltlux.filedepot.service;

import java.util.concurrent.Executor;

import org.springframework.stereotype.Service;

import com.saltlux.filedepot.config.FileDepotProperties;
import com.saltlux.filedepot.config.FileDepotProperties.EmbedKitProvider;
import com.saltlux.filedepot.config.FileDepotProperties.ParsekitScenario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingQueue {

  private final Executor executor;
  private final ProcessingService processingService;
  private final FileDepotProperties properties;

  public void submit(String uuid) {
    if (!isParsingEnabled()) {
      log.debug("Parsing is disabled, skipping processing for: {}", uuid);
      return;
    }

    executor.execute(() -> processAsync(uuid));
    log.info("Submitted file for processing: {}", uuid);
  }

  private void processAsync(String uuid) {
    log.info("[{}] Starting async processing pipeline. Embedding enabled: {}", uuid, isEmbeddingEnabled());

    try {
      // Step 1: Extract
      log.info("[{}] Step 1/3: Starting extraction...", uuid);
      processingService.extract(uuid);
      log.info("[{}] Step 1/3: Extraction completed", uuid);

      // Step 2: Chunk (if embedding enabled)
      if (isEmbeddingEnabled()) {
        log.info("[{}] Step 2/3: Starting chunking...", uuid);
        processingService.chunk(uuid);
        log.info("[{}] Step 2/3: Chunking completed", uuid);

        // Step 3: Embed
        log.info("[{}] Step 3/3: Starting embedding...", uuid);
        processingService.embed(uuid);
        log.info("[{}] Step 3/3: Embedding completed", uuid);
      } else {
        log.info("[{}] Embedding disabled, skipping chunk and embed steps", uuid);
      }

      log.info("[{}] Processing pipeline completed successfully", uuid);

    } catch (Exception e) {
      log.error("[{}] Processing pipeline FAILED at some step: {}", uuid, e.getMessage(), e);
    }
  }

  private boolean isParsingEnabled() {
    ParsekitScenario scenario = properties.getParsekit().getScenario();
    return scenario == ParsekitScenario.SCENARIO1 || scenario == ParsekitScenario.SCENARIO2;
  }

  private boolean isEmbeddingEnabled() {
    EmbedKitProvider provider = properties.getEmbedkit().getProvider();
    return provider != EmbedKitProvider.NONE;
  }
}
