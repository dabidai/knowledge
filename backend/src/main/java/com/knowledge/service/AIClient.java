package com.knowledge.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/** AI 服务 HTTP 客户端 —— 调用 Python FastAPI */
@Slf4j
@Service
public class AIClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${ai-service.url}")
    private String aiServiceUrl;

    public AIClient(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;
    }

    /** 生成 Embedding */
    public float[] embed(String text) {
        try {
            EmbedRequest req = new EmbedRequest(text);
            String json = objectMapper.writeValueAsString(req);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiServiceUrl + "/embed"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            EmbedResponse resp = objectMapper.readValue(response.body(), EmbedResponse.class);
            return resp.embedding;
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

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiServiceUrl + "/ask"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(java.time.Duration.ofMinutes(3))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            AskResponse resp = objectMapper.readValue(response.body(), AskResponse.class);
            return resp.answer;
        } catch (Exception e) {
            log.error("RAG 问答失败", e);
            return "抱歉，AI 服务暂时不可用。";
        }
    }

    /** 健康检查 */
    public boolean health() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiServiceUrl + "/health"))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // -- 请求/响应模型 --

    @Data
    public static class EmbedRequest {
        private String text;
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
        public AskRequest(String question, List<String> contexts) {
            this.question = question;
            this.contexts = contexts;
        }
    }

    @Data
    public static class AskResponse {
        private String answer;
    }
}
