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

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "item")
    List<Document> findByDeptNameAndIsPublic(String deptName, Boolean isPublic);

    @org.springframework.data.jpa.repository.Query("SELECT d.fileId FROM Document d LEFT JOIN d.item i " +
            "WHERE :admin = true OR ((d.isPublic = true OR d.deptName = :deptName) " +
            "AND (i IS NULL OR i.isPublic = true OR i.deptName = :deptName))")
    List<String> findAccessibleIds(boolean admin, String deptName);
}
