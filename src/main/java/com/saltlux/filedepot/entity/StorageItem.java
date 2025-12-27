package com.saltlux.filedepot.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Builder
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(exclude = { "id", "createdAt" })
@ToString(exclude = { "id", "createdAt" })
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "storage_item", uniqueConstraints = {
    @UniqueConstraint(name = "UK_storage_item_uuid", columnNames = "uuid")
}, indexes = {
    @Index(name = "IDX_storage_item_processing_step", columnList = "processing_step"),
    @Index(name = "IDX_storage_item_deleted", columnList = "deleted")
})
@DynamicInsert
@DynamicUpdate
public class StorageItem {
  @PrePersist
  public void generateUuid() {
    if (this.uuid == null) {
      this.uuid = UUID.randomUUID().toString();
    }
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String uuid;

  @Column(name = "content_type", length = 255)
  private String contentType;

  @Column(nullable = false)
  private Long size;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "processing_step", nullable = false, length = 20)
  private ProcessingStep processingStep = ProcessingStep.PENDING;

  @CreatedDate
  @Column(nullable = false)
  private Instant createdAt;

  @Builder.Default
  @Column(nullable = false)
  private boolean deleted = false;

  @Builder.Default
  @Column(name = "retry_count", nullable = false)
  private int retryCount = 0;

  public void markAsDeleted() {
    this.deleted = true;
  }

  public void updateStep(ProcessingStep step) {
    this.processingStep = step;
  }

  public void incrementRetryCount() {
    this.retryCount++;
  }

  public void resetRetryCount() {
    this.retryCount = 0;
  }
}
