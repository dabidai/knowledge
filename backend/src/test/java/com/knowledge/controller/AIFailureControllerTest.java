package com.knowledge.controller;

import com.knowledge.entity.User;
import com.knowledge.entity.Department;
import com.knowledge.repository.ItemRepository;
import com.knowledge.repository.OpinionRepository;
import com.knowledge.service.AIClient;
import com.knowledge.service.ConversationService;
import com.knowledge.service.ElasticsearchService;
import com.knowledge.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AIFailureControllerTest {
    private final AIClient ai = mock(AIClient.class);
    private final ElasticsearchService es = mock(ElasticsearchService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final ItemRepository items = mock(ItemRepository.class);
    private final OpinionRepository opinions = mock(OpinionRepository.class);
    private final User user = User.builder().id(1L).role("default")
            .department(Department.builder().name("A").build()).build();
    private final ChatController chat = new ChatController(es, ai, permissions, conversations, items, opinions);
    private final SearchController search = new SearchController(es, ai, permissions);

    @BeforeEach void setup() throws Exception {
        when(conversations.getMessages(1L, 1L)).thenReturn(List.of());
        when(ai.embed(anyString())).thenReturn(new float[1024]);
        var docs = List.of(ElasticsearchService.DocIndex.builder().docId("a").content("资料").build());
        when(es.hybridSearch(anyString(), any(), any(), anyInt(), anyString(), anyString(), anyString())).thenReturn(docs);
        when(permissions.filterSearchResults(eq(user), any())).thenReturn(docs);
        when(items.searchByTitleKeyword(anyString(), anyBoolean(), anyString(), any())).thenReturn(List.of());
    }

    @Test void generationFailureNeverPersistsAssistantAnswer() {
        when(ai.chat(anyString(), any(), any())).thenThrow(new AIClient.AIServiceException(503, "模型不可用"));
        var response = chat.chat(Map.of("question", "q", "conversationId", 1L), user);
        assertEquals(503, response.getCode());
        assertNull(response.getData());
        verify(conversations).addMessage(1L, 1L, "user", "q", null);
        verify(conversations, never()).addMessage(anyLong(), anyLong(), eq("assistant"), anyString(), any());
    }

    @Test void embeddingFailureStillAllowsChatWithVisibleDegradation() throws Exception {
        when(ai.embed(anyString())).thenThrow(new AIClient.AIServiceException(503, "向量不可用"));
        when(ai.chat(anyString(), any(), any())).thenReturn("有依据的回答");
        var response = chat.chat(Map.of("question", "q", "conversationId", 1L), user);
        assertEquals(200, response.getCode());
        assertTrue(response.getData().isDegraded());
        assertNotNull(response.getData().getWarning());
        verify(es).hybridSearch(eq("q"), isNull(), any(), eq(5), eq(""), eq(""), eq(""));
        verify(conversations).addMessage(eq(1L), eq(1L), eq("assistant"), eq("有依据的回答"), any());
    }

    @Test void searchReportsEmbeddingDegradation() {
        when(ai.embed(anyString())).thenThrow(new AIClient.AIServiceException(503, "向量不可用"));
        when(ai.ask(anyString(), any())).thenReturn("answer");
        var response = search.search(Map.of("query", "q"), user);
        assertEquals(200, response.getCode());
        assertTrue(response.getData().isDegraded());
        assertFalse(response.getData().getSources().isEmpty());
    }

    @Test void searchGenerationFailureIsNotSuccessfulResult() {
        when(ai.ask(anyString(), any())).thenThrow(new AIClient.AIServiceException(504, "模型超时"));
        var response = search.search(Map.of("query", "q"), user);
        assertEquals(504, response.getCode());
        assertNull(response.getData());
    }

    @Test void invalidLimitsAreRejectedBeforeAnyModelOrConversationWrite() {
        assertEquals(400, chat.chat(Map.of("question", "x".repeat(1001)), user).getCode());
        assertEquals(400, search.search(Map.of("query", "q", "topK", 100), user).getCode());
        assertEquals(400, chat.chat(Map.of("question", 42), user).getCode());
        assertEquals(400, search.search(Map.of("query", "q", "topK", "5"), user).getCode());
        verifyNoInteractions(ai, conversations);
    }
}
