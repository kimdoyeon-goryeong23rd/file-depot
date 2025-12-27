package com.saltlux.filedepot.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import io.minio.MinioClient;

@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

  private static final String MINIO_USER = "minioadmin";
  private static final String MINIO_PASSWORD = "minioadmin";
  private static final String MINIO_BUCKET = "file-depot-test";

  @Bean
  @ServiceConnection
  public MariaDBContainer<?> mariaDbContainer() {
    return new MariaDBContainer<>(DockerImageName.parse("mariadb:11.2"))
        .withDatabaseName("file_depot_test")
        .withUsername("test")
        .withPassword("test");
  }

  @Bean
  public MinIOContainer minioContainer() {
    return new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
        .withUserName(MINIO_USER)
        .withPassword(MINIO_PASSWORD);
  }

  @Bean
  @Primary
  public MinioClient testMinioClient(MinIOContainer minioContainer) {
    return MinioClient.builder()
        .endpoint(minioContainer.getS3URL())
        .credentials(MINIO_USER, MINIO_PASSWORD)
        .build();
  }

  @Bean
  @Primary
  public FileDepotProperties testFileDepotProperties(MinIOContainer minioContainer) {
    FileDepotProperties props = new FileDepotProperties();
    props.getMinio().setUrl(minioContainer.getS3URL());
    props.getMinio().setAccessKey(MINIO_USER);
    props.getMinio().setSecretKey(MINIO_PASSWORD);
    props.getMinio().setBucket(MINIO_BUCKET);
    return props;
  }
}
