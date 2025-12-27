package com.saltlux.filedepot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "extracted_content")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExtractedContent {

  @Id
  private Long id;

  @OneToOne
  @MapsId
  @JoinColumn(name = "id")
  private StorageItem storageItem;

  @Lob
  @Column(columnDefinition = "LONGTEXT")
  private String content;

  public ExtractedContent(StorageItem storageItem, String content) {
    this.storageItem = storageItem;
    this.content = content;
  }
}
