package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.SearchResult;
import com.knowledge.entity.Item;
import com.knowledge.entity.Opinion;
import com.knowledge.entity.User;
import com.knowledge.security.AccessScope;
import com.knowledge.service.PermissionService;
import org.springframework.security.access.AccessDeniedException;
import com.knowledge.repository.ItemRepository;
import com.knowledge.repository.OpinionRepository;
import com.knowledge.service.AIClient;
import com.knowledge.service.ElasticsearchService;
import com.knowledge.service.ElasticsearchService.DocIndex;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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
    private final PermissionService permissionService;
    private final com.knowledge.service.ConversationService conversationService;
    private final ItemRepository itemRepo;
    private final OpinionRepository opinionRepo;

    /** 判断 AI 回答是否表明"未找到相关内容" */
    private boolean isNoRelevantAnswer(String answer) {
        if (answer == null || answer.isBlank()) return true;
        String s = answer;
        return s.contains("无法确定") || s.contains("没有相关") || s.contains("未找到")
                || s.contains("不足以") || s.contains("没有找到")
                || s.contains("暂无相关") || s.contains("无法回答");
    }

    /**
     * 智能体对话 —— 多轮上下文 + RAG 检索增强。
     *
     * @param body { question, history: [{role,content}], topK, conversationId }
     * @param user 当前用户（用于权限过滤）
     * @return 对话结果（答案 + 来源文档 + conversationId）
     */
    @PostMapping
    public ApiResponse<ChatResult> chat(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {

        if (!(body.get("question") instanceof String question)) {
            return ApiResponse.error(400, "问题必须为文本");
        }
        if (!(body.getOrDefault("topK", 5) instanceof Integer topK)) {
            return ApiResponse.error(400, "topK 必须为整数");
        }
        Object convIdObj = body.get("conversationId");
        Long conversationId = convIdObj instanceof Number ? ((Number) convIdObj).longValue() : null;

        if (question == null || question.isBlank()) {
            return ApiResponse.error(400, "问题不能为空");
        }
        if (question.length() > 1000 || topK < 1 || topK > 10) {
            return ApiResponse.error(400, "问题最多 1000 字，topK 必须在 1 到 10 之间");
        }

        try {
            // 1. 自动创建或复用对话
            if (conversationId == null) {
                conversationId = conversationService.createConversation(
                        user.getId(), question).getId();
            }

            // 复用会话必须先校验所有者；历史只从服务端加载。
            List<Map<String, String>> history = conversationService.getMessages(conversationId, user.getId())
                    .stream().map(m -> Map.of("role", m.getRole(), "content", m.getContent())).toList();
            if (history.size() > 20) history = history.subList(history.size() - 20, history.size());

            // 2. 保存用户消息
            conversationService.addMessage(conversationId, user.getId(), "user", question, null);

            // 3. 生成查询向量
            float[] queryVector = null;
            boolean degraded = false;
            try {
                queryVector = aiClient.embed(question);
                degraded = queryVector == null || queryVector.length == 0;
            } catch (AIClient.AIServiceException e) {
                degraded = true;
                log.warn("聊天向量生成失败，降级为关键词检索");
            }

            // 4. ES 混合检索
            AccessScope scope = AccessScope.of(user);
            List<DocIndex> docs = esService.hybridSearch(question, queryVector,
                    scope, topK, "", "", "");

            docs = permissionService.filterSearchResults(user, docs);

            // 5. 从结构化数据库搜索事项和签阅记录
            List<String> contexts = new ArrayList<>();
            StringBuilder structCtx = new StringBuilder();

            List<Item> matchedItems = itemRepo.searchByTitleKeyword(
                    question, scope.admin(), scope.department(), PageRequest.of(0, 10));
            if (!matchedItems.isEmpty()) {
                structCtx.append("\n【相关事项及签阅记录】\n");
                for (Item item : matchedItems) {
                    structCtx.append(String.format(
                            "事项: %s | 分类: %s | 文号: %s | 发文单位: %s | 年度: %s | 类型: %s\n",
                            item.getTitle(),
                            item.getCategory() != null ? item.getCategory() : "-",
                            item.getCategoryNo() != null ? item.getCategoryNo() : "-",
                            item.getIssuer() != null ? item.getIssuer() : "-",
                            item.getYear() != null ? item.getYear() : "-",
                            item.getItemType() != null ? item.getItemType() : "-"));
                    // 查询该事项的签阅记录
                    List<Opinion> opinions = opinionRepo.findByItemItemId(item.getItemId());
                    if (!opinions.isEmpty()) {
                        structCtx.append("  签阅记录:\n");
                        for (Opinion o : opinions) {
                            structCtx.append(String.format("    - %s (%s): %s\n",
                                    o.getSigner(),
                                    o.getSignTime() != null ? o.getSignTime().toString() : "未知时间",
                                    o.getContent() != null ? o.getContent() : ""));
                        }
                    }
                }
            }
            if (!structCtx.isEmpty()) {
                contexts.add(structCtx.toString());
            }

            // 6. 整理来源文档
            Set<String> seenFileIds = new HashSet<>();
            List<SearchResult.SourceDoc> sources = new ArrayList<>();

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

            // 8. 智能体对话（带历史 + RAG 上下文 + 结构化事项数据）
            String answer = aiClient.chat(question, contexts, history);

            // 9. 如果 AI 回答表明未找到相关内容，清空来源文档列表
            if (isNoRelevantAnswer(answer)) {
                sources.clear();
            }

            // 10. 保存 AI 回答
            conversationService.addMessage(conversationId, user.getId(), "assistant", answer, sources);

            ChatResult result = ChatResult.builder()
                    .answer(answer)
                    .sources(sources)
                    .conversationId(conversationId)
                    .degraded(degraded)
                    .warning(degraded ? "语义检索暂不可用，本次已使用关键词检索" : null)
                    .build();

            return ApiResponse.ok(result);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (AIClient.AIServiceException e) {
            return ApiResponse.error(e.getStatus(), e.getMessage());
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
        /** 对话 ID（前端用于后续追加消息） */
        private Long conversationId;
        private boolean degraded;
        private String warning;
    }
}
