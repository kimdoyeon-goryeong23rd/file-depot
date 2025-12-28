package com.saltlux.filedepot.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import com.saltlux.filedepot.entity.Chunk;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {

  List<Chunk> findByUuidOrderByChunkIndexAsc(String uuid);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  void deleteByUuid(String uuid);
}
