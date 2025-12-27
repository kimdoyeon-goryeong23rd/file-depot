package com.saltlux.filedepot.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AsyncConfig {

  private final FileDepotProperties properties;

  @Bean
  public Executor executor() {
    var processing = properties.getProcessing();
    int workerThreads = processing.getWorkerThreads();
    int queueCapacity = processing.getQueueCapacity();

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(workerThreads);
    executor.setMaxPoolSize(workerThreads * 2);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("processing-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(120);
    executor.initialize();

    log.info("Initialized executor: corePoolSize={}, maxPoolSize={}, queueCapacity={}",
        workerThreads, workerThreads * 2, queueCapacity);

    return executor;
  }
}
