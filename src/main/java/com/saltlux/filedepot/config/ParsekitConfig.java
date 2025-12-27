package com.saltlux.filedepot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import lombok.RequiredArgsConstructor;
import me.hanju.parsekit.ConverterClient;
import me.hanju.parsekit.docling.DoclingClient;
import me.hanju.parsekit.vlm.VlmClient;

@Configuration
@RequiredArgsConstructor
public class ParsekitConfig {

  private final FileDepotProperties properties;

  @Bean
  @ConditionalOnExpression("'${file-depot.parsekit.scenario}' == 'scenario1' or '${file-depot.parsekit.scenario}' == 'scenario2'")
  public ConverterClient converterClient(WebClient.Builder webClientBuilder) {
    return new ConverterClient(webClientBuilder, properties.getParsekit().getConverter().getUrl());
  }

  @Bean
  @ConditionalOnProperty(name = "file-depot.parsekit.scenario", havingValue = "scenario1")
  public DoclingClient doclingClient(WebClient.Builder webClientBuilder) {
    return new DoclingClient(webClientBuilder, properties.getParsekit().getDocling().getUrl());
  }

  @Bean
  @ConditionalOnExpression("'${file-depot.parsekit.scenario}' == 'scenario1' or '${file-depot.parsekit.scenario}' == 'scenario2'")
  public VlmClient vlmClient(WebClient.Builder webClientBuilder) {
    var vlm = properties.getParsekit().getVlm();
    return new VlmClient(webClientBuilder, vlm.getUrl(), vlm.getModel());
  }
}
