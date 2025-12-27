package com.saltlux.filedepot.entity;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "file_content", indexes = {
    @Index(name = "idx_file_content_uuid", columnList = "uuid"),
    @Index(name = "idx_file_content_uuid_chunk", columnList = "uuid, chunk_index")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FileContent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String uuid;

  @Column(name = "chunk_index", nullable = false)
  private Integer chunkIndex;

  @Lob
  @Column(name = "extracted_text", columnDefinition = "TEXT")
  private String extractedText;

  @Lob
  @Column(name = "embedding", columnDefinition = "LONGBLOB")
  private byte[] embedding;

  @CreatedDate
  @Column(nullable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(nullable = false)
  private Instant updatedAt;

  @Builder
  public FileContent(String uuid, Integer chunkIndex, String extractedText, byte[] embedding) {
    this.uuid = uuid;
    this.chunkIndex = chunkIndex != null ? chunkIndex : 0;
    this.extractedText = extractedText;
    this.embedding = embedding;
  }

  public void updateEmbedding(byte[] embedding) {
    this.embedding = embedding;
  }
}
