package com.saltlux.filedepot.service;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.saltlux.filedepot.config.FileDepotProperties;
import com.saltlux.filedepot.config.FileDepotProperties.ParsekitScenario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.hanju.parsekit.ConverterClient;
import me.hanju.parsekit.docling.DoclingClient;
import me.hanju.parsekit.payload.ConvertResult;
import me.hanju.parsekit.payload.ImageConvertResult;
import me.hanju.parsekit.payload.ImagePage;
import me.hanju.parsekit.payload.ParseResult;
import me.hanju.parsekit.vlm.VlmClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class TextExtractor {

  private static final Pattern BASE64_IMAGE_PATTERN = Pattern
      .compile("!\\[Image\\]\\(data:image/([^;]+);base64,([^)]+)\\)");

  private final StorageClient storageClient;
  private final FileDepotProperties properties;

  @Autowired(required = false)
  private ConverterClient converterClient;

  @Autowired(required = false)
  private DoclingClient doclingClient;

  @Autowired(required = false)
  private VlmClient vlmClient;

  /**
   * MinIO에서 파일을 가져와 텍스트를 추출합니다.
   *
   * @param uuid 파일 UUID
   * @param contentType 파일 MIME 타입
   * @return 추출된 텍스트
   * @throws IllegalStateException parsekit이 비활성화된 경우
   */
  public String extract(String uuid, String contentType) {
    ParsekitScenario scenario = properties.getParsekit().getScenario();

    if (scenario == ParsekitScenario.DISABLED) {
      throw new IllegalStateException("Parsekit is disabled");
    }

    byte[] fileBytes = storageClient.getObjectBytes(uuid);
    String filename = uuid + getExtensionFromContentType(contentType);

    return switch (scenario) {
      case SCENARIO1 -> extractTextScenario1(fileBytes, filename, contentType, uuid);
      case SCENARIO2 -> extractTextScenario2(fileBytes, filename, contentType, uuid);
      default -> throw new IllegalStateException("Unknown scenario: " + scenario);
    };
  }

  private String extractTextScenario1(byte[] fileBytes, String filename, String contentType, String uuid) {
    ConvertResult pdfResult = converterClient.convert(fileBytes, filename, contentType);
    log.debug("Scenario1: Converted to PDF: {} bytes, converted={}", pdfResult.size(), pdfResult.converted());

    ParseResult doclingResult = doclingClient.parse(pdfResult.content(), pdfResult.filename());
    String markdown = doclingResult.asMarkdown();

    if (vlmClient != null && vlmClient.isAvailable()) {
      markdown = processBase64Images(markdown);
    }

    log.info("Scenario1: Extracted {} characters from file {}", markdown.length(), uuid);
    return markdown;
  }

  private String extractTextScenario2(byte[] fileBytes, String filename, String contentType, String uuid) {
    ImageConvertResult imagesResult = converterClient.convertToImages(fileBytes, filename, contentType, "png", 150);
    log.debug("Scenario2: Converted to {} images", imagesResult.totalPages());

    StringBuilder result = new StringBuilder();
    for (ImagePage page : imagesResult.pages()) {
      String ocrText = vlmClient.ocr(page.content());
      result.append("## 페이지 ").append(page.page()).append("\n\n");
      result.append(ocrText).append("\n\n");
    }

    log.info("Scenario2: Extracted {} characters from {} pages for file {}",
        result.length(), imagesResult.totalPages(), uuid);
    return result.toString();
  }

  private String processBase64Images(String markdown) {
    Matcher matcher = BASE64_IMAGE_PATTERN.matcher(markdown);
    StringBuilder result = new StringBuilder();

    while (matcher.find()) {
      String base64Data = matcher.group(2);
      try {
        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
        String ocrText = vlmClient.ocr(imageBytes);
        matcher.appendReplacement(result, Matcher.quoteReplacement(ocrText));
      } catch (Exception e) {
        log.warn("이미지 OCR 처리 실패, 원본 유지: {}", e.getMessage());
        matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
      }
    }
    matcher.appendTail(result);

    return result.toString();
  }

  private String getExtensionFromContentType(String contentType) {
    if (contentType == null) {
      return "";
    }
    return switch (contentType) {
      case "application/pdf" -> ".pdf";
      case "application/msword" -> ".doc";
      case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
      case "application/vnd.ms-powerpoint" -> ".ppt";
      case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
      case "application/vnd.ms-excel" -> ".xls";
      case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
      case "application/x-hwp" -> ".hwp";
      case "application/haansofthwp" -> ".hwp";
      case "application/vnd.hancom.hwpx" -> ".hwpx";
      default -> "";
    };
  }
}
