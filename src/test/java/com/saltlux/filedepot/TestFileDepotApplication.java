package com.saltlux.filedepot;

import org.springframework.boot.SpringApplication;

import com.saltlux.filedepot.config.TestContainersConfig;

/**
 * 로컬 개발용 Application.
 * Testcontainers로 MariaDB, MinIO를 자동으로 띄웁니다.
 *
 * 실행: ./gradlew bootTestRun
 */
public class TestFileDepotApplication {

  public static void main(String[] args) {
    SpringApplication.from(FileDepotApplication::main)
        .with(TestContainersConfig.class)
        .run(args);
  }
}
