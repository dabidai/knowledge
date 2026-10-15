package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.SearchResult;
import com.knowledge.entity.User;
import com.knowledge.security.AccessScope;
import com.knowledge.service.PermissionService;
import org.springframework.security.access.AccessDeniedException;
import com.knowledge.security.RateLimit;
import com.knowledge.service.AIClient;
import com.knowledge.service.ElasticsearchService;
import com.knowledge.service.ElasticsearchService.DocIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 知识检索控制器 */
@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final ElasticsearchService esService;
    private final AIClient aiClient;
    private final PermissionService permissionService;

    /** 判断 AI 回答是否表明"未找到相关内容" */
    private boolean isNoRelevantAnswer(String answer) {
        if (answer == null || answer.isBlank()) return true;
        String s = answer;
        return s.contains("无法确定") || s.contains("没有相关") || s.contains("未找到")
                || s.contains("不足以") || s.contains("没有找到")
                || s.contains("暂无相关") || s.contains("无法回答");
    }

    /** 综合检索 —— 返回 RAG 答案 + 来源文档 + 下载链接，支持分类/年度/部门筛选 */
    @PostMapping
    @RateLimit(maxRequests = 30, windowSeconds = 60)
    public ApiResponse<SearchResult> search(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {

        if (!(body.get("query") instanceof String query)) {
            return ApiResponse.error(400, "查询必须为文本");
        }
        if (!(body.getOrDefault("topK", 5) instanceof Integer topK)) {
            return ApiResponse.error(400, "topK 必须为整数");
        }
        String category = (String) body.getOrDefault("category", "");
        String year = (String) body.getOrDefault("year", "");
        String itemType = (String) body.getOrDefault("itemType", "");

        if (query == null || query.isBlank()) {
            return ApiResponse.error(400, "查询内容不能为空");
        }
        if (query.length() > 1000 || topK < 1 || topK > 10) {
            return ApiResponse.error(400, "查询最多 1000 字，topK 必须在 1 到 10 之间");
        }

        try {
            // 1. 生成查询向量
            float[] queryVector = null;
            boolean degraded = false;
            try {
                queryVector = aiClient.embed(query);
                degraded = queryVector == null || queryVector.length == 0;
            } catch (AIClient.AIServiceException e) {
                degraded = true;
                log.warn("查询向量生成失败，降级为关键词检索");
            }

            // 2. ES 混合检索（带元数据筛选）
            AccessScope scope = AccessScope.of(user);
            List<DocIndex> docs = esService.hybridSearch(query, queryVector, scope, topK,
                    category, year, itemType);

            docs = permissionService.filterSearchResults(user, docs);

            // 3. 整理来源文档
            Set<String> seenFileIds = new HashSet<>();
            List<SearchResult.SourceDoc> sources = new ArrayList<>();
            List<String> contexts = new ArrayList<>();

            for (DocIndex doc : docs) {
                contexts.add(doc.getContent());

                if (seenFileIds.add(doc.getDocId())) {
                    String downloadUrl = "/api/browse/download/" + java.net.URLEncoder.encode(
                            doc.getDocId(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
                    sources.add(SearchResult.SourceDoc.builder()
                            .fileId(doc.getDocId())
                            .fileName(doc.getFileName())
                            .snippet(doc.getSnippet())
                            .downloadUrl(downloadUrl)
                            .deptName(doc.getDeptName())
                            .build());
                }
            }

            // 4. RAG 问答 (通过 Python 服务调用 Ollama)
            String answer;
            if (!contexts.isEmpty()) {
                answer = aiClient.ask(query, contexts);
            } else {
                answer = "未找到相关文档。";
            }

            // 5. 如果 AI 回答表明未找到相关内容，清空来源文档列表
            //    避免出现"AI 说没有 + 下边却列出不相关文档"的体验问题
            if (isNoRelevantAnswer(answer)) {
                sources.clear();
            }

            SearchResult result = SearchResult.builder()
                    .answer(answer)
                    .sources(sources)
                    .relatedItems(Collections.emptyList()) // Neo4j 后续接入
                    .degraded(degraded)
                    .warning(degraded ? "语义检索暂不可用，本次已使用关键词检索" : null)
                    .build();

            return ApiResponse.ok(result);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (AIClient.AIServiceException e) {
            return ApiResponse.error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            log.error("检索失败", e);
            return ApiResponse.error(500, "检索服务异常: " + e.getMessage());
        }
    }
}
