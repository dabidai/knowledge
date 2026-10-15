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
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Python AI 客户端。失败抛出明确异常，由调用方决定是否允许降级。 */
@Slf4j
@Service
public class AIClient {
    private static final int VECTOR_DIMENSIONS = 1024; // 与 ES contentVector 映射一致
    private final RestClient restClient;
    private final RestClient batchClient;
    private final RestClient generationClient;
    private final RestClient healthClient;
    private final ObjectMapper objectMapper;

    @Value("${ai-service.url}")
    private String aiServiceUrl;

    public AIClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        restClient = client(Duration.ofSeconds(30));
        batchClient = client(Duration.ofSeconds(180));
        generationClient = client(Duration.ofSeconds(190));
        healthClient = client(Duration.ofSeconds(5));
    }

    private static RestClient client(Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    private <T> T post(RestClient client, String path, Object request, Class<T> responseType) {
        try {
            T response = client.post().uri(aiServiceUrl + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request)).retrieve().body(responseType);
            if (response == null) throw new AIServiceException(502, "AI 服务返回空响应");
            return response;
        } catch (AIServiceException e) {
            throw e;
        } catch (Exception e) {
            int status = 503;
            String message = "AI 服务暂时不可用，请稍后重试";
            if (e instanceof RestClientResponseException response) {
                int upstream = response.getStatusCode().value();
                if (upstream == 413 || upstream == 422) {
                    status = 422;
                    message = "请求内容超过 AI 服务限制，请缩短问题或上下文";
                } else if (upstream == 504) {
                    status = 504;
                    message = "模型响应超时，请稍后重试";
                }
            }
            log.warn("AI 调用失败 path={} type={}", path, e.getClass().getSimpleName());
            throw new AIServiceException(status, message, e);
        }
    }

    public float[] embed(String text) {
        EmbedResponse response = post(restClient, "/embed", new EmbedRequest(text), EmbedResponse.class);
        validateVector(response.embedding);
        return response.embedding;
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        EmbedBatchResponse response = post(batchClient, "/embed-batch",
                new EmbedBatchRequest(texts), EmbedBatchResponse.class);
        if (response.embeddings == null || response.embeddings.size() != texts.size()) {
            throw new AIServiceException(502, "AI 服务返回的向量数量不匹配");
        }
        response.embeddings.forEach(AIClient::validateVector);
        return response.embeddings;
    }

    private static void validateVector(float[] vector) {
        if (vector == null || vector.length != VECTOR_DIMENSIONS) {
            throw new AIServiceException(502, "AI 服务返回的向量维度不匹配");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new AIServiceException(502, "AI 服务返回无效向量数值");
        }
    }

    private static String answer(AskResponse response) {
        if (response.answer == null || response.answer.isBlank()) {
            throw new AIServiceException(502, "AI 服务返回空答案");
        }
        if (response.inputTruncated) log.info("AI 输入已按模型预算裁剪");
        return response.answer;
    }

    public String ask(String question, List<String> contexts) {
        return answer(post(generationClient, "/ask", new AskRequest(question, contexts), AskResponse.class));
    }

    public String chat(String question, List<String> contexts, List<Map<String, String>> history) {
        return answer(post(generationClient, "/chat", new ChatRequest(question, contexts, history), AskResponse.class));
    }

    /** readiness 确认模型已加载、Ollama 可达且所需模型存在；不触发推理。 */
    public boolean health() {
        try {
            String response = healthClient.get().uri(aiServiceUrl + "/health/ready")
                    .retrieve().body(String.class);
            return response != null && "UP".equals(objectMapper.readTree(response).path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    public static class AIServiceException extends RuntimeException {
        private final int status;
        public AIServiceException(int status, String message) { this(status, message, null); }
        public AIServiceException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }
        public int getStatus() { return status; }
    }

    public record EmbedRequest(String text) {}
    public record EmbedBatchRequest(List<String> texts) {}
    public record AskRequest(String question, List<String> contexts) {}
    public record ChatRequest(String question, List<String> contexts, List<Map<String, String>> history) {}

    @Data
    public static class EmbedResponse {
        private float[] embedding;
    }

    @Data
    public static class EmbedBatchResponse {
        private List<float[]> embeddings;
    }

    @Data
    public static class AskResponse {
        private String answer;
        @JsonProperty("input_truncated")
        private boolean inputTruncated;
    }
}
