package com.knowledge.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** AI 服务 HTTP 客户端 —— 调用 Python FastAPI */
@Slf4j
@Service
public class AIClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${ai-service.url}")
    private String aiServiceUrl;

    public AIClient(ObjectMapper objectMapper) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.objectMapper = objectMapper;
    }

    /** 生成 Embedding */
    public float[] embed(String text) {
        try {
            EmbedRequest req = new EmbedRequest(text);
            String json = objectMapper.writeValueAsString(req);
            log.debug("Embed 请求: {}", json);
            EmbedResponse resp = restClient.post()
                    .uri(aiServiceUrl + "/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(EmbedResponse.class);
            return resp != null ? resp.embedding : new float[0];
        } catch (Exception e) {
            log.error("Embedding 生成失败", e);
            return new float[0];
        }
    }

    /** RAG 问答 */
    public String ask(String question, List<String> contexts) {
        try {
            AskRequest req = new AskRequest(question, contexts);
            String json = objectMapper.writeValueAsString(req);
            AskResponse resp = restClient.post()
                    .uri(aiServiceUrl + "/ask")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(AskResponse.class);
            return resp != null ? resp.answer : "抱歉，AI 服务暂时不可用。";
        } catch (Exception e) {
            log.error("RAG 问答失败", e);
            return "抱歉，AI 服务暂时不可用。";
        }
    }

    /** 智能体对话 */
    public String chat(String question, List<String> contexts,
                       List<Map<String, String>> history) {
        try {
            ChatRequest req = new ChatRequest(question, contexts, history);
            String json = objectMapper.writeValueAsString(req);
            log.debug("Chat 请求: {}", json);
            AskResponse resp = restClient.post()
                    .uri(aiServiceUrl + "/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(AskResponse.class);
            return resp != null ? resp.answer : "抱歉，AI 服务暂时不可用。";
        } catch (Exception e) {
            log.error("智能体对话失败", e);
            return "抱歉，AI 服务暂时不可用。";
        }
    }

    /** 健康检查 */
    public boolean health() {
        try {
            String resp = restClient.get()
                    .uri(aiServiceUrl + "/health")
                    .retrieve()
                    .body(String.class);
            return resp != null && resp.contains("UP");
        } catch (Exception e) {
            return false;
        }
    }

    // -- 请求/响应模型 --

    @Data
    public static class EmbedRequest {
        private String text;
        public EmbedRequest() {}
        public EmbedRequest(String text) { this.text = text; }
    }

    @Data
    public static class EmbedResponse {
        @JsonProperty("embedding")
        private float[] embedding;
    }

    @Data
    public static class AskRequest {
        private String question;
        private List<String> contexts;
        public AskRequest() {}
        public AskRequest(String question, List<String> contexts) {
            this.question = question;
            this.contexts = contexts;
        }
    }

    @Data
    public static class AskResponse {
        private String answer;
    }

    @Data
    public static class ChatRequest {
        private String question;
        private List<String> contexts;
        @JsonProperty("history")
        private List<Map<String, String>> history;
        public ChatRequest() {}
        public ChatRequest(String question, List<String> contexts,
                           List<Map<String, String>> history) {
            this.question = question;
            this.contexts = contexts;
            this.history = history;
        }
    }
}
