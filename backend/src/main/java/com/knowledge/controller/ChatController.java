package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.SearchResult;
import com.knowledge.entity.User;
import com.knowledge.service.AIClient;
import com.knowledge.service.ElasticsearchService;
import com.knowledge.service.ElasticsearchService.DocIndex;
import com.knowledge.service.MinioService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 智能体对话控制器 —— 支持多轮对话 + RAG 知识检索。
 *
 * <p>与 Search 的区别：
 * <ul>
 *   <li>接收对话历史，保持上下文连贯性</li>
 *   <li>返回的 sources 在每次对话中都是最新的检索结果</li>
 *   <li>AI 回答会引用具体的文档编号</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ElasticsearchService esService;
    private final AIClient aiClient;
    private final MinioService minioService;

    /**
     * 智能体对话 —— 多轮上下文 + RAG 检索增强。
     *
     * @param body { question, history: [{role,content}], topK }
     * @param user 当前用户（用于权限过滤）
     * @return 对话结果（答案 + 来源文档）
     */
    @PostMapping
    public ApiResponse<ChatResult> chat(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {

        String question = (String) body.getOrDefault("question", "");
        int topK = (int) body.getOrDefault("topK", 5);

        if (question == null || question.isBlank()) {
            return ApiResponse.error(400, "问题不能为空");
        }

        try {
            // 1. 生成查询向量
            float[] queryVector = aiClient.embed(question);

            // 2. ES 混合检索
            String deptName = user.getDepartment().getName();
            List<DocIndex> docs = esService.hybridSearch(question, queryVector,
                    deptName, topK, "", "", "");

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

            // 4. 提取对话历史
            @SuppressWarnings("unchecked")
            List<Map<String, String>> history = (List<Map<String, String>>)
                    body.getOrDefault("history", Collections.emptyList());

            // 5. 智能体对话（带历史 + RAG 上下文）
            String answer = aiClient.chat(question, contexts, history);

            ChatResult result = ChatResult.builder()
                    .answer(answer)
                    .sources(sources)
                    .build();

            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("对话失败", e);
            return ApiResponse.error(500, "对话服务异常: " + e.getMessage());
        }
    }

    /** 对话结果 */
    @Data
    @Builder
    public static class ChatResult {
        /** AI 回答 */
        private String answer;
        /** 来源文档 */
        private List<SearchResult.SourceDoc> sources;
    }
}
