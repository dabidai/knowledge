package com.knowledge.repository;

import com.knowledge.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    /** 按文件名模糊匹配（用于关联实际文件） */
    Optional<Document> findByFileNameEndingWith(String suffix);

    List<Document> findByItemItemId(String itemId);

    List<Document> findByImportBatch(String batchId);

    List<Document> findByStatus(String status);
}
