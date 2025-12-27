package com.saltlux.filedepot;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.saltlux.filedepot.integration.BaseIntegrationTest;

/**
 * Integration test that requires Docker and TestContainers.
 *
 * Run manually with: ./gradlew test --tests FileDepotApplicationTests
 * when Docker is available.
 */
@Disabled("Requires Docker with TestContainers - run manually when Docker is available")
class FileDepotApplicationTests extends BaseIntegrationTest {

  @Test
  void contextLoads() {
    // This test verifies that the Spring application context loads successfully
    // with all TestContainers running.
    // If the context loads without throwing an exception, the test passes.
    org.junit.jupiter.api.Assertions.assertTrue(true, "Context loaded successfully");
  }
}
