package com.knowledge.service;

import com.knowledge.entity.Document;
import com.knowledge.entity.Item;
import com.knowledge.entity.User;
import com.knowledge.repository.DocumentRepository;
import com.knowledge.repository.ItemRepository;
import com.knowledge.security.AccessScope;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** PostgreSQL 为权限归属的权威来源；索引仅用于召回。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionService {
    private final DocumentRepository documents;
    private final ItemRepository items;

    public boolean canRead(AccessScope scope, Document doc) {
        return scope.allows(doc.getDeptName(), doc.getIsPublic())
                && (doc.getItem() == null || scope.allows(doc.getItem().getDeptName(), doc.getItem().getIsPublic()));
    }

    public Document requireDocument(User user, String id) {
        AccessScope scope = AccessScope.of(user);
        Document doc = documents.findById(id).orElseThrow(() -> new AccessDeniedException("文档不可访问"));
        if (!canRead(scope, doc)) throw new AccessDeniedException("文档不可访问");
        return doc;
    }

    public Item requireItem(User user, String id) {
        AccessScope scope = AccessScope.of(user);
        Item item = items.findById(id).orElseThrow(() -> new AccessDeniedException("事项不可访问"));
        if (!scope.allows(item.getDeptName(), item.getIsPublic())) throw new AccessDeniedException("事项不可访问");
        return item;
    }

    public List<Item> accessibleItems(User user) {
        AccessScope scope = AccessScope.of(user);
        return scope.admin() ? items.findAll() : items.findAccessibleByDept(scope.department());
    }

    public List<Document> readableDocuments(User user, Collection<String> ids) {
        AccessScope scope = AccessScope.of(user);
        return documents.findAllById(ids).stream().filter(d -> canRead(scope, d)).toList();
    }

    public List<String> accessibleDocumentIds(User user) {
        AccessScope scope = AccessScope.of(user);
        return documents.findAccessibleIds(scope.admin(), scope.department());
    }

    /** 过滤过期/缺失权限索引，来源路径和展示元信息回填自数据库。 */
    public List<ElasticsearchService.DocIndex> filterSearchResults(User user, List<ElasticsearchService.DocIndex> hits) {
        Map<String, Document> allowed = readableDocuments(user, hits.stream()
                .map(ElasticsearchService.DocIndex::getDocId).distinct().toList()).stream()
                .collect(Collectors.toMap(Document::getFileId, d -> d));
        return hits.stream().filter(h -> allowed.containsKey(h.getDocId())).peek(h -> {
            Document doc = allowed.get(h.getDocId());
            h.setFileName(doc.getFileName());
            h.setDeptName(doc.getDeptName());
            h.setMinioPath(doc.getMinioPath());
        }).toList();
    }
}
