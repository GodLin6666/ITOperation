package com.opsai.repo;

import com.opsai.entity.KbChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 知识分块仓库 */
public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {

    List<KbChunk> findByDocId(Long docId);

    void deleteByDocId(Long docId);

    long countByDocId(Long docId);
}
