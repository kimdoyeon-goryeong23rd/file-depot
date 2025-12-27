package com.saltlux.filedepot.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;
import me.hanju.filedepot.api.dto.CommonResponseDto;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<CommonResponseDto<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
    log.warn("IllegalArgumentException: {}", e.getMessage());
    return ResponseEntity.badRequest().body(CommonResponseDto.error(e.getMessage()));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<CommonResponseDto<Void>> handleIllegalStateException(IllegalStateException e) {
    log.warn("IllegalStateException: {}", e.getMessage());
    return ResponseEntity.badRequest().body(CommonResponseDto.error(e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<CommonResponseDto<Void>> handleException(Exception e) {
    log.error("Unhandled exception", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(CommonResponseDto.error("Internal server error"));
  }
}
