package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.SearchResult;
import com.knowledge.entity.User;
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

    /** 综合检索 —— 返回 RAG 答案 + 来源文档 + 下载链接 */
    @PostMapping
    @Cacheable(value = "search", key = "#body['query'] + '_' + #body['topK'] + '_' + #user.department.name")
    public ApiResponse<SearchResult> search(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {

        String query = (String) body.getOrDefault("query", "");
        int topK = (int) body.getOrDefault("topK", 5);

        if (query == null || query.isBlank()) {
            return ApiResponse.error(400, "查询内容不能为空");
        }

        try {
            // 1. 生成查询向量
            float[] queryVector = aiClient.embed(query);

            // 2. ES 混合检索
            String deptName = user.getDepartment().getName();
            List<DocIndex> docs = esService.hybridSearch(query, queryVector, deptName, topK);

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
