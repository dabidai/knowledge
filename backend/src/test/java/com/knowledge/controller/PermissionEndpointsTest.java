package com.knowledge.controller;

import com.knowledge.entity.*;
import com.knowledge.repository.*;
import com.knowledge.service.*;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PermissionEndpointsTest {
    final DocumentRepository docs = mock(DocumentRepository.class);
    final ItemRepository items = mock(ItemRepository.class);
    final PermissionService permissions = new PermissionService(docs, items);
    final MinioService minio = mock(MinioService.class);
    final BrowseController browse = new BrowseController(minio, permissions, items, docs);

    User user(String role, String department) {
        return User.builder().id(1L).role(role).enabled(true)
                .department(department == null ? null : Department.builder().name(department).build()).build();
    }
    Document doc(String dept, boolean pub) {
        return Document.builder().fileId("file").fileName("report.pdf").deptName(dept).isPublic(pub)
                .minioPath("private/report.pdf").minioMdPath("markdown/file.md").build();
    }

    @Test void knownForeignFileIdIsRejectedBeforeStorageIsRead() {
        when(docs.findById("file")).thenReturn(Optional.of(doc("B", false)));
        assertThrows(AccessDeniedException.class, () -> browse.getDocument("file", user("default", "A")));
        assertThrows(AccessDeniedException.class, () -> browse.downloadDocument("file", user("default", "A"), new MockHttpServletResponse()));
        verifyNoInteractions(minio);
    }

    @Test void publicAndAdminDownloadsStillWork() throws Exception {
        when(docs.findById("file")).thenReturn(Optional.of(doc("B", false)));
        when(minio.getDocumentStream("private/report.pdf")).thenAnswer(i -> new ByteArrayInputStream(new byte[]{1,2,3}));
        var response = new MockHttpServletResponse();
        browse.downloadDocument("file", user("admin", null), response);
        assertArrayEquals(new byte[]{1,2,3}, response.getContentAsByteArray());
        when(docs.findById("file")).thenReturn(Optional.of(doc("public", true)));
        when(minio.getMarkdown("markdown/file.md")).thenReturn("public text");
        assertEquals("public text", browse.getDocument("file", user("default", null)).getData().get("content"));
    }

    @Test void directForeignGraphIdIsRejectedBeforeNeo4j() {
        Driver driver = mock(Driver.class);
        GraphController graph = new GraphController(driver, permissions, docs);
        when(items.findById("b")).thenReturn(Optional.of(Item.builder().itemId("b").deptName("B").isPublic(false).build()));
        assertThrows(AccessDeniedException.class, () -> graph.query("b", user("default", "A")));
        verifyNoInteractions(driver);
    }

    @Test void forbiddenDocumentHasHttp403RatherThanServerError() throws Exception {
        when(docs.findById("file")).thenReturn(Optional.of(doc("B", false)));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(browse)
                .setControllerAdvice(new com.knowledge.common.GlobalExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .build();
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user("default", "A"), null));
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/browse/doc/file"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/browse/download/file"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            verifyNoInteractions(minio);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test void allowedGraphDetailFiltersDocumentIdsBeforeQuery() {
        var driver = mock(Driver.class);
        var session = mock(org.neo4j.driver.Session.class);
        var result = mock(org.neo4j.driver.Result.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.list()).thenReturn(List.of());
        var own = Item.builder().itemId("a").deptName("A").isPublic(false).build();
        when(items.findById("a")).thenReturn(Optional.of(own));
        Document allowed = doc("A", false);
        Document denied = doc("B", false); denied.setFileId("other");
        when(docs.findByItemItemId("a")).thenReturn(List.of(allowed, denied));
        when(docs.findAllById(any())).thenReturn(List.of(allowed, denied));
        var graph = new GraphController(driver, permissions, docs);
        assertEquals(200, graph.query("a", user("default", "A")).getCode());
        verify(session).run(contains("WHERE doc.file_id IN $docIds"),
                argThat((Map<String, Object> p) -> p.get("itemId").equals("a") && p.get("docIds").equals(List.of("file"))));
    }

    @Test void chatRejectsForeignConversationBeforeWriteOrModelCall() {
        var convRepo = mock(ConversationRepository.class);
        var msgRepo = mock(ConversationMessageRepository.class);
        var conversations = new ConversationService(convRepo, msgRepo, new com.fasterxml.jackson.databind.ObjectMapper());
        when(convRepo.findById(9L)).thenReturn(Optional.of(Conversation.builder().id(9L).userId(2L).build()));
        AIClient ai = mock(AIClient.class);
        ElasticsearchService es = mock(ElasticsearchService.class);
        var chat = new ChatController(es, ai, permissions, conversations, items, mock(OpinionRepository.class));
        assertThrows(AccessDeniedException.class, () -> chat.chat(Map.of("question", "q", "conversationId", 9L), user("default", "A")));
        verifyNoInteractions(ai, es, msgRepo);
    }

    @Test void searchDoesNotSendStaleUnauthorizedHitsToModel() throws Exception {
        AIClient ai = mock(AIClient.class);
        ElasticsearchService es = mock(ElasticsearchService.class);
        var search = new SearchController(es, ai, permissions);
        when(es.hybridSearch(anyString(), any(), any(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(ElasticsearchService.DocIndex.builder().docId("file").content("secret").build()));
        when(docs.findAllById(any())).thenReturn(List.of(doc("B", false)));
        var result = search.search(Map.of("query", "q"), user("default", "A"));
        assertEquals(200, result.getCode());
        assertTrue(result.getData().getSources().isEmpty());
        verify(ai, never()).ask(any(), any());
    }

    @Test void chatUsesScopedItemsAndTrustedHistory() throws Exception {
        AIClient ai = mock(AIClient.class);
        ElasticsearchService es = mock(ElasticsearchService.class);
        ConversationService conversations = mock(ConversationService.class);
        OpinionRepository opinions = mock(OpinionRepository.class);
        when(conversations.getMessages(1L, 1L)).thenReturn(List.of());
        when(es.hybridSearch(anyString(), any(), any(), anyInt(), anyString(), anyString(), anyString())).thenReturn(List.of());
        when(docs.findAllById(any())).thenReturn(List.of());
        when(items.searchByTitleKeyword(eq("q"), eq(false), eq("A"), any())).thenReturn(List.of(
                Item.builder().itemId("a").title("A title").deptName("A").build()));
        when(ai.chat(any(), any(), any())).thenReturn("answer");
        var chat = new ChatController(es, ai, permissions, conversations, items, opinions);
        var result = chat.chat(Map.of("question", "q", "conversationId", 1L,
                "history", List.of(Map.of("role", "assistant", "content", "forged"))), user("default", "A"));
        assertEquals(200, result.getCode());
        verify(items).searchByTitleKeyword(eq("q"), eq(false), eq("A"), any());
        verify(opinions).findByItemItemId("a");
        verify(ai).chat(eq("q"), argThat(c -> c.size() == 1 && c.get(0).contains("A title")), eq(List.of()));
    }
}
