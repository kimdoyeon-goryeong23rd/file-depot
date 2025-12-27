package com.saltlux.filedepot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.saltlux.embedkit.TextEmbeddingClient;
import com.saltlux.embedkit.luxia.LuxiaTextEmbeddingClient;
import com.saltlux.embedkit.vllm.VllmTextEmbeddingClient;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class EmbedKitConfig {

  private final FileDepotProperties properties;

  @Bean
  @ConditionalOnProperty(name = "file-depot.embedkit.provider", havingValue = "vllm")
  public TextEmbeddingClient vllmTextEmbeddingClient(WebClient.Builder webClientBuilder) {
    var vllm = properties.getEmbedkit().getVllm();
    return new VllmTextEmbeddingClient(webClientBuilder, vllm.getUrl(), vllm.getModel(), vllm.getBatchSize());
  }

  @Bean
  @ConditionalOnProperty(name = "file-depot.embedkit.provider", havingValue = "luxia")
  public TextEmbeddingClient luxiaTextEmbeddingClient(WebClient.Builder webClientBuilder) {
    var luxia = properties.getEmbedkit().getLuxia();
    return new LuxiaTextEmbeddingClient(webClientBuilder, luxia.getBaseUrl(), luxia.getBatchSize());
  }
}
