package com.knowledge.repository;

import com.knowledge.entity.ImportTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ImportTaskRepository extends JpaRepository<ImportTask, Long> {
    Optional<ImportTask> findByBatchId(String batchId);
    List<ImportTask> findAllByOrderByCreatedAtDesc();
}
