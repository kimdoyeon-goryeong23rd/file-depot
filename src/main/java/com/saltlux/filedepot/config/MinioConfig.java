package com.saltlux.filedepot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableConfigurationProperties(FileDepotProperties.class)
@RequiredArgsConstructor
public class MinioConfig {

  private final FileDepotProperties properties;

  @Bean
  public MinioClient minioClient() {
    var minio = properties.getMinio();
    return MinioClient.builder()
        .endpoint(minio.getUrl())
        .credentials(minio.getAccessKey(), minio.getSecretKey())
        .build();
  }
}
