package com.saltlux.filedepot.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.saltlux.filedepot.service.FileService;

import lombok.RequiredArgsConstructor;
import me.hanju.filedepot.api.dto.BatchDownloadRequest;
import me.hanju.filedepot.api.dto.ChunkDto;
import me.hanju.filedepot.api.dto.CommonResponseDto;
import me.hanju.filedepot.api.dto.ConfirmUploadRequest;
import me.hanju.filedepot.api.dto.DownloadUrlResponse;
import me.hanju.filedepot.api.dto.StorageItemDto;
import me.hanju.filedepot.api.dto.UploadUrlResponse;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

  private final FileService fileService;

  @PostMapping("/prepare-upload")
  public CommonResponseDto<UploadUrlResponse> prepareUpload() {
    UploadUrlResponse response = fileService.prepareUpload();
    return CommonResponseDto.success(response);
  }

  @PostMapping("/confirm-upload")
  public CommonResponseDto<StorageItemDto> confirmUpload(@RequestBody ConfirmUploadRequest request) {
    StorageItemDto item = fileService.confirmUpload(request);
    return CommonResponseDto.success(item);
  }

  @GetMapping("/{id}")
  public CommonResponseDto<StorageItemDto> getFileMetadata(
      @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean withContent) {
    StorageItemDto item = fileService.getFileMetadata(id, withContent);
    return CommonResponseDto.success(item);
  }

  @GetMapping("/{uuid}/download-url")
  public CommonResponseDto<DownloadUrlResponse> getDownloadUrl(@PathVariable String uuid) {
    DownloadUrlResponse response = fileService.getDownloadUrl(uuid);
    return CommonResponseDto.success(response);
  }

  @GetMapping("/{id}/chunks")
  public CommonResponseDto<List<ChunkDto>> getChunks(
      @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean withEmbedding) {
    List<ChunkDto> chunks = fileService.getChunks(id, withEmbedding);
    return CommonResponseDto.success(chunks);
  }

  @PostMapping("/download/batch")
  public ResponseEntity<StreamingResponseBody> downloadBatch(@RequestBody BatchDownloadRequest request) {
    StreamingResponseBody stream = fileService.downloadBatch(request);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"files.zip\"")
        .body(stream);
  }

  @PostMapping("/delete")
  public CommonResponseDto<Void> deleteFiles(@RequestBody List<String> uuids) {
    fileService.deleteFiles(uuids);
    return CommonResponseDto.success(null);
  }
}
