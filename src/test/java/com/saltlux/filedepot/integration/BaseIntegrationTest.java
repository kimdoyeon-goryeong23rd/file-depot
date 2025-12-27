package com.saltlux.filedepot.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.saltlux.filedepot.config.TestContainersConfig;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
public abstract class BaseIntegrationTest {
  // Base class for integration tests
  // All integration tests should extend this class
}
