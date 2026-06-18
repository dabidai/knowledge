package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.entity.Document;
import com.knowledge.entity.Item;
import com.knowledge.entity.User;
import com.knowledge.repository.DocumentRepository;
import com.knowledge.repository.ItemRepository;
import com.knowledge.service.MinioService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 结构化浏览控制器 —— 提供文档目录树和 Markdown 内容查询。
 * 用户只能看到本部门及公共区的文档，admin 可查看全部。
 */
@Slf4j
@RestController
@RequestMapping("/api/browse")
@RequiredArgsConstructor
public class BrowseController {

    /** MinIO 文件存储服务 */
    private final MinioService minioService;
    /** 事项元数据仓库 */
    private final ItemRepository itemRepo;
    /** 文档映射仓库 */
    private final DocumentRepository docRepo;

    /**
     * 获取文档目录树，按「部门 → 事项分类 → 事项 → 文件」四级结构组织。
     * 缓存 30 分钟，按用户部门隔离。
     *
     * @param user 当前认证用户（用于权限过滤）
     * @return 四级树形结构
     */
    @GetMapping("/tree")
    @org.springframework.cache.annotation.Cacheable(value = "browseTree", key = "#user.department.name")
    public ApiResponse<List<TreeNode>> tree(@AuthenticationPrincipal User user) {
        if (user.getDepartment() == null) {
            return ApiResponse.error(400, "当前用户未关联部门，无法浏览文档目录");
        }
        String deptName = user.getDepartment().getName();
        boolean isAdmin = "admin".equals(user.getRole());

        List<Item> items;
        if (isAdmin) {
            items = itemRepo.findAll();
        } else {
            items = itemRepo.findAccessibleByDept(deptName);
        }

        // 按部门 → 事项分类 → 事项 → 文档 四级分组
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

        // 转换为前端 Tree 组件所需的结构
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
     * 获取文档的结构化 Markdown 内容。
     *
     * @param fileId 文件ID
     * @return 包含 fileId 和 Markdown 内容的 Map
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
            return ApiResponse.error(500, "获取文档失败，请确认 MinIO 服务已启动且文档已导入");
        }
    }

    /**
     * 下载原始文档 —— 代理 MinIO 文件流，强制浏览器下载（而非内联打开）。
     *
     * @param fileId   文件ID（对应 Document.fileId 和 MinIO 中的对象路径）
     * @param response HTTP 响应（用于流式写出文件）
     */
    @GetMapping("/download/{fileId}")
    public void downloadDocument(@PathVariable String fileId,
                                  HttpServletResponse response) throws IOException {
        // 1. 查找文档记录获取元信息
        Document doc = docRepo.findById(fileId).orElse(null);
        String objectPath;
        String downloadFileName;

        if (doc != null && doc.getMinioPath() != null) {
            objectPath = doc.getMinioPath();
            downloadFileName = doc.getFileName();
            // 提取纯文件名（去掉路径部分）
            if (downloadFileName.contains("/")) {
                downloadFileName = downloadFileName.substring(downloadFileName.lastIndexOf('/') + 1);
            }
        } else {
            // 兼容旧数据：尝试用 fileId 直接构造路径
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "文档不存在: " + fileId);
            return;
        }

        // 2. 从 MinIO 获取文件流
        try (InputStream in = minioService.getDocumentStream(objectPath)) {
            if (in == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件内容不存在");
                return;
            }

            // 3. 设置响应头 —— 强制下载
            String encodedName = URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + encodedName
                    + "\"; filename*=UTF-8''" + encodedName);
            response.setCharacterEncoding("UTF-8");

            // 4. 流式写出
            try (OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }
        } catch (Exception e) {
            log.error("下载文档失败: fileId={}, objectPath={}", fileId, objectPath, e);
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "下载失败");
            }
        }
    }

    // -- 树形结构 DTO --

    /** 文档树节点，支持部门/分类/事项/文档四种类型 */
    @Data
    @Builder
    public static class TreeNode {
        /** 节点显示名称 */
        private String label;
        /** 节点类型：department | category | item | document */
        private String type;
        /** 事项详情（type=item 时有值） */
        private ItemNode item;
        /** 文档详情（type=document 时有值） */
        private DocNode doc;
        /** 子节点列表 */
        private List<TreeNode> children;

        /** 事项摘要 */
        @Data
        @Builder
        public static class ItemNode {
            /** 事项ID */
            private String itemId;
            /** 事项标题 */
            private String title;
            /** 分类编号，如 "2021通1234" */
            private String categoryNo;
            /** 年度 */
            private String year;
            /** 发文单位 */
            private String issuer;
            /** 包含的文档列表 */
            private List<DocNode> docs;
        }

        /** 文档摘要 */
        @Data
        @Builder
        public static class DocNode {
            /** 文件ID */
            private String fileId;
            /** 文件名/路径 */
            private String fileName;
            /** 状态：expected / matched / orphan */
            private String status;
            /** MinIO 中 Markdown 存储路径 */
            private String minioMdPath;
        }
    }
}
