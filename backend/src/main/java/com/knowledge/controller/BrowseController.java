package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.entity.Document;
import com.knowledge.entity.Item;
import com.knowledge.entity.User;
import com.knowledge.repository.DocumentRepository;
import com.knowledge.repository.ItemRepository;
import com.knowledge.service.MinioService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/** 结构化浏览控制器 */
@Slf4j
@RestController
@RequestMapping("/api/browse")
@RequiredArgsConstructor
public class BrowseController {

    private final MinioService minioService;
    private final ItemRepository itemRepo;
    private final DocumentRepository docRepo;

    /**
     * 获取文档目录树
     * 结构: 部门 → 事项分类 → 事项 → 文件列表
     */
    @GetMapping("/tree")
    @org.springframework.cache.annotation.Cacheable(value = "browseTree", key = "#user.department.name")
    public ApiResponse<List<TreeNode>> tree(@AuthenticationPrincipal User user) {
        String deptName = user.getDepartment().getName();
        boolean isAdmin = "admin".equals(user.getRole());

        // 获取用户可访问的事项
        List<Item> items;
        if (isAdmin) {
            items = itemRepo.findAll();
        } else {
            items = itemRepo.findAccessibleByDept(deptName);
        }

        // 按部门分组 → 事项分类 → 事项 → 文档
        Map<String, Map<String, List<TreeNode.ItemNode>>> deptMap = new LinkedHashMap<>();

        for (Item item : items) {
            String dept = item.getIsPublic() ? "公共区" : item.getDeptName();
            String cat = item.getCategory() != null ? item.getCategory() : "未分类";

            deptMap.computeIfAbsent(dept, k -> new LinkedHashMap<>());
            Map<String, List<TreeNode.ItemNode>> catMap = deptMap.get(dept);
            catMap.computeIfAbsent(cat, k -> new ArrayList<>());

            List<Document> docs = docRepo.findByItemItemId(item.getItemId());
            List<TreeNode.DocNode> docNodes = docs.stream()
                    .map(d -> TreeNode.DocNode.builder()
                            .fileId(d.getFileId())
                            .fileName(d.getFileName())
                            .status(d.getStatus())
                            .minioMdPath(d.getMinioMdPath())
                            .build())
                    .collect(Collectors.toList());

            catMap.get(cat).add(TreeNode.ItemNode.builder()
                    .itemId(item.getItemId())
                    .title(item.getTitle())
                    .categoryNo(item.getCategoryNo())
                    .year(item.getYear())
                    .issuer(item.getIssuer())
                    .docs(docNodes)
                    .build());
        }

        // 转换为前端树形结构
        List<TreeNode> tree = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<TreeNode.ItemNode>>> deptEntry : deptMap.entrySet()) {
            List<TreeNode> catNodes = new ArrayList<>();
            for (Map.Entry<String, List<TreeNode.ItemNode>> catEntry : deptEntry.getValue().entrySet()) {
                catNodes.add(TreeNode.builder()
                        .label(catEntry.getKey())
                        .type("category")
                        .children(catEntry.getValue().stream()
                                .map(item -> TreeNode.builder()
                                        .label(item.getTitle())
                                        .type("item")
                                        .item(item)
                                        .children(item.getDocs().stream()
                                                .map(doc -> TreeNode.builder()
                                                        .label(doc.getFileName())
                                                        .type("document")
                                                        .doc(doc)
                                                        .build())
                                                .collect(Collectors.toList()))
                                        .build())
                                .collect(Collectors.toList()))
                        .build());
            }
            tree.add(TreeNode.builder()
                    .label(deptEntry.getKey())
                    .type("department")
                    .children(catNodes)
                    .build());
        }

        return ApiResponse.ok(tree);
    }

    /**
     * 获取 Markdown 文档内容
     */
    @GetMapping("/doc/{fileId}")
    public ApiResponse<Map<String, String>> getDocument(@PathVariable String fileId) {
        try {
            String objectPath = "markdown/" + fileId + ".md";
            String content = minioService.getMarkdown(objectPath);
            if (content == null) {
                return ApiResponse.error(404, "文档内容不存在: " + fileId);
            }
            return ApiResponse.ok(Map.of("fileId", fileId, "content", content));
        } catch (Exception e) {
            log.error("获取文档失败: {}", fileId, e);
            return ApiResponse.error(500, "获取文档失败: " + e.getMessage());
        }
    }

    // -- DTO --

    @Data
    @Builder
    public static class TreeNode {
        private String label;
        private String type;  // department | category | item | document
        private ItemNode item;
        private DocNode doc;
        private List<TreeNode> children;

        @Data
        @Builder
        public static class ItemNode {
            private String itemId;
            private String title;
            private String categoryNo;
            private String year;
            private String issuer;
            private List<DocNode> docs;
        }

        @Data
        @Builder
        public static class DocNode {
            private String fileId;
            private String fileName;
            private String status;
            private String minioMdPath;
        }
    }
}
