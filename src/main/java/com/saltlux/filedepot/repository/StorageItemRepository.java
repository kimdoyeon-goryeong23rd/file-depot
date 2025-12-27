package com.saltlux.filedepot.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.saltlux.filedepot.entity.ProcessingStep;
import com.saltlux.filedepot.entity.StorageItem;

@Repository
public interface StorageItemRepository extends JpaRepository<StorageItem, Long> {

    Optional<StorageItem> findByUuid(String uuid);

    Optional<StorageItem> findByUuidAndDeletedFalse(String uuid);

    List<StorageItem> findByUuidInAndDeletedFalse(List<String> uuids);

    List<StorageItem> findByProcessingStepAndDeletedFalseOrderByCreatedAtAsc(ProcessingStep step, Pageable pageable);

    List<StorageItem> findByProcessingStepInAndDeletedFalseOrderByCreatedAtAsc(List<ProcessingStep> steps, Pageable pageable);

    long countByProcessingStepAndDeletedFalse(ProcessingStep step);

    List<StorageItem> findByDeletedTrue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    void deleteByUuidIn(List<String> uuids);
}
