package com.knowledge.repository;

import com.knowledge.entity.ImportTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ImportTaskRepository extends JpaRepository<ImportTask, Long> {
    Optional<ImportTask> findByBatchId(String batchId);
    Page<ImportTask> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
