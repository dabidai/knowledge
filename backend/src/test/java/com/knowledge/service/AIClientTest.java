package com.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AIClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AIClient ai;
    private MockRestServiceServer server;

    @BeforeEach void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var client = builder.build();
        ai = new AIClient(mapper);
        for (String name : List.of("restClient", "batchClient", "generationClient", "healthClient")) {
            ReflectionTestUtils.setField(ai, name, client);
        }
        ReflectionTestUtils.setField(ai, "aiServiceUrl", "http://ai");
    }

    private void batch(Object vectors) throws Exception {
        server.expect(requestTo("http://ai/embed-batch"))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("embeddings", vectors)), MediaType.APPLICATION_JSON));
    }

    @Test void batchPreservesCountAndOrder() throws Exception {
        float[] first = new float[1024]; first[0] = 0.1f;
        float[] second = new float[1024]; second[0] = 0.2f;
        batch(List.of(first, second));
        var result = ai.embedBatch(List.of("a", "b"));
        assertArrayEquals(first, result.get(0));
        assertArrayEquals(second, result.get(1));
        server.verify();
    }

    @Test void shortBatchIsFailure() throws Exception {
        batch(List.of(new float[1024]));
        assertThrows(AIClient.AIServiceException.class, () -> ai.embedBatch(List.of("a", "b")));
    }

    @Test void wrongDimensionsAreFailure() throws Exception {
        batch(List.of(new float[3]));
        assertThrows(AIClient.AIServiceException.class, () -> ai.embedBatch(List.of("a")));
    }

    @Test void nonFiniteVectorIsFailure() throws Exception {
        float[] vector = new float[1024]; vector[0] = Float.NaN;
        batch(List.of(vector));
        assertThrows(AIClient.AIServiceException.class, () -> ai.embedBatch(List.of("a")));
    }

    @Test void singleVectorIsValidated() {
        server.expect(requestTo("http://ai/embed")).andRespond(withSuccess("{\"embedding\":[1]}", MediaType.APPLICATION_JSON));
        assertThrows(AIClient.AIServiceException.class, () -> ai.embed("q"));
    }

    @Test void upstreamFailureDoesNotBecomeAnAnswer() {
        server.expect(requestTo("http://ai/chat")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        var error = assertThrows(AIClient.AIServiceException.class, () -> ai.chat("q", List.of(), List.of()));
        assertEquals(503, error.getStatus());
    }

    @Test void upstreamTimeoutIsExplicit() {
        server.expect(requestTo("http://ai/ask")).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        var error = assertThrows(AIClient.AIServiceException.class, () -> ai.ask("q", List.of("ctx")));
        assertEquals(504, error.getStatus());
    }

    @Test void upstreamValidationIsExplicit() {
        server.expect(requestTo("http://ai/chat")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        var error = assertThrows(AIClient.AIServiceException.class, () -> ai.chat("q", List.of(), List.of()));
        assertEquals(422, error.getStatus());
    }

    @Test void emptyAnswerIsFailure() {
        server.expect(requestTo("http://ai/ask")).andRespond(withSuccess("{\"answer\":\" \"}", MediaType.APPLICATION_JSON));
        assertThrows(AIClient.AIServiceException.class, () -> ai.ask("q", List.of()));
    }

    @Test void readinessIsParsedInsteadOfSearchingForUpSubstring() {
        server.expect(requestTo("http://ai/health/ready"))
                .andRespond(withSuccess("{\"status\":\"DEGRADED\",\"embedding\":\"UP\"}", MediaType.APPLICATION_JSON));
        assertFalse(ai.health());
    }
}
