package com.saltlux.filedepot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "file-depot")
public class FileDepotProperties {

  private MinioProperties minio = new MinioProperties();
  private ParsekitProperties parsekit = new ParsekitProperties();
  private EmbedKitProperties embedkit = new EmbedKitProperties();
  private Processing processing = new Processing();

  @Getter
  @RequiredArgsConstructor
  public enum ParsekitScenario {
    DISABLED("disabled"),
    SCENARIO1("scenario1"),  // Converter → Docling → VLM
    SCENARIO2("scenario2");  // Converter → VLM (direct)

    private final String value;
  }

  @Getter
  @RequiredArgsConstructor
  public enum EmbedKitProvider {
    NONE("none"),
    VLLM("vllm"),
    LUXIA("luxia");

    private final String value;
  }

  @Getter
  @Setter
  public static class MinioProperties {
    private String url;
    private String accessKey;
    private String secretKey;
    private String bucket;
  }

  @Getter
  @Setter
  public static class ParsekitProperties {
    private ParsekitScenario scenario = ParsekitScenario.DISABLED;
    private ConverterProperties converter = new ConverterProperties();
    private DoclingProperties docling = new DoclingProperties();
    private VlmProperties vlm = new VlmProperties();

    @Getter
    @Setter
    public static class ConverterProperties {
      private String url;
    }

    @Getter
    @Setter
    public static class DoclingProperties {
      private String url;
    }

    @Getter
    @Setter
    public static class VlmProperties {
      private String url;
      private String model;
    }
  }

  @Getter
  @Setter
  public static class EmbedKitProperties {
    private EmbedKitProvider provider = EmbedKitProvider.NONE;
    private VllmProperties vllm = new VllmProperties();
    private LuxiaProperties luxia = new LuxiaProperties();

    @Getter
    @Setter
    public static class VllmProperties {
      private String url;
      private String model;
      private int batchSize = 32;
    }

    @Getter
    @Setter
    public static class LuxiaProperties {
      private String baseUrl;
      private int batchSize = 32;
    }
  }

  @Getter
  @Setter
  public static class Processing {
    private int workerThreads = 4;
    private int queueCapacity = 1000;
    private int maxRetryCount = 3;
    private ChunkingProperties chunking = new ChunkingProperties();
    private BatchProperties batch = new BatchProperties();

    @Getter
    @Setter
    public static class ChunkingProperties {
      private int size = 512;
      private int overlap = 100;
    }

    @Getter
    @Setter
    public static class BatchProperties {
      private boolean enabled = false;
      private int batchSize = 100;
      private String extractCron = "0 */5 * * * *";
      private String chunkCron = "0 */5 * * * *";
      private String embedCron = "0 */5 * * * *";
      private String orphanCleanupCron = "0 0 * * * *";
    }
  }
}
