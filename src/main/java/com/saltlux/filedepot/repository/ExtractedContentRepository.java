package com.saltlux.filedepot.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.saltlux.filedepot.entity.ExtractedContent;

@Repository
public interface ExtractedContentRepository extends JpaRepository<ExtractedContent, Long> {

    Optional<ExtractedContent> findByStorageItemUuid(String uuid);

    void deleteByStorageItemUuid(String uuid);
}
