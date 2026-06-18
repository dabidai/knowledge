package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.SearchResult;
import com.knowledge.entity.User;
import com.knowledge.security.RateLimit;
import com.knowledge.service.AIClient;
import com.knowledge.service.ElasticsearchService;
import com.knowledge.service.ElasticsearchService.DocIndex;
import com.knowledge.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
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
    private final MinioService minioService;

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
    @Cacheable(value = "search", key = "#body['query'] + '_' + #body['topK'] + '_' " +
            "+ #body['category'] + '_' + #body['year'] + '_' + #user.department.name",
            unless = "#result.code != 200")
    public ApiResponse<SearchResult> search(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {

        String query = (String) body.getOrDefault("query", "");
        int topK = (int) body.getOrDefault("topK", 5);
        String category = (String) body.getOrDefault("category", "");
        String year = (String) body.getOrDefault("year", "");
        String itemType = (String) body.getOrDefault("itemType", "");

        if (query == null || query.isBlank()) {
            return ApiResponse.error(400, "查询内容不能为空");
        }

        try {
            // 1. 生成查询向量
            float[] queryVector = aiClient.embed(query);

            // 2. ES 混合检索（带元数据筛选）
            String deptName = user.getDepartment().getName();
            List<DocIndex> docs = esService.hybridSearch(query, queryVector, deptName, topK,
                    category, year, itemType);

            // 3. 整理来源文档
            Set<String> seenFileIds = new HashSet<>();
            List<SearchResult.SourceDoc> sources = new ArrayList<>();
            List<String> contexts = new ArrayList<>();

            for (DocIndex doc : docs) {
                contexts.add(doc.getContent());

                if (seenFileIds.add(doc.getDocId())) {
                    String downloadUrl = minioService.getPresignedUrl(doc.getMinioPath());
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
                    .build();

            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("检索失败", e);
            return ApiResponse.error(500, "检索服务异常: " + e.getMessage());
        }
    }
}
