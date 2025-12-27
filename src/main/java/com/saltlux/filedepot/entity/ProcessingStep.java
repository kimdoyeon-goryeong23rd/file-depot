package com.saltlux.filedepot.entity;

public enum ProcessingStep {
  PENDING,
  PROCESSING,
  EXTRACTED,
  CHUNKED,
  EMBEDDED,
  FAILED
}
