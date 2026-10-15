package com.knowledge.controller;

import com.knowledge.entity.*;
import com.knowledge.repository.*;
import com.knowledge.service.*;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PermissionCacheTest {
    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheTestConfig {
        @Bean CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("search", "browseTree", "graphOverview");
        }
    }

    @Test void enabledCachingCannotReuseAdminOrRevokedContent() throws Exception {
        ItemRepository items = mock(ItemRepository.class);
        DocumentRepository docs = mock(DocumentRepository.class);
        var permissions = new PermissionService(docs, items);
        var ai = mock(AIClient.class);
        var es = mock(ElasticsearchService.class);
        var driver = mock(Driver.class);
        var session = mock(Session.class);
        var result = mock(Result.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.list()).thenReturn(List.of());
        when(es.hybridSearch(anyString(), any(), any(), anyInt(), anyString(), anyString(), anyString())).thenReturn(List.of());
        when(docs.findAllById(any())).thenReturn(List.of());
        Item b = Item.builder().itemId("b").title("B secret").deptName("B").isPublic(false).category("c").build();
        when(items.findAll()).thenReturn(List.of(b));
        when(items.findAccessibleByDept("A")).thenReturn(List.of());
        User admin = User.builder().id(1L).role("admin").enabled(true).department(Department.builder().name("A").build()).build();
        User member = User.builder().id(2L).role("default").enabled(true).department(admin.getDepartment()).build();

        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(CacheTestConfig.class);
            context.registerBean(BrowseController.class, () -> new BrowseController(mock(MinioService.class), permissions, items, docs));
            context.registerBean(GraphController.class, () -> new GraphController(driver, permissions, docs));
            context.registerBean(SearchController.class, () -> new SearchController(es, ai, permissions));
            context.refresh();
            CacheManager caches = context.getBean(CacheManager.class);
            caches.getCache("browseTree").put("A", "old unsafe result");
            caches.getCache("graphOverview").put("A", "old unsafe result");
            caches.getCache("search").put("q_null_null_null_A", "old unsafe result");
            var browse = context.getBean(BrowseController.class);
            assertFalse(browse.tree(admin).getData().isEmpty());
            assertTrue(browse.tree(member).getData().isEmpty());
            when(items.findAll()).thenReturn(List.of()); // resource revoked/deleted
            assertTrue(browse.tree(admin).getData().isEmpty());
            var graph = context.getBean(GraphController.class);
            graph.query(null, admin);
            graph.query(null, member);
            var search = context.getBean(SearchController.class);
            assertEquals(200, search.search(Map.of("query", "q"), admin).getCode());
            assertEquals(200, search.search(Map.of("query", "q"), member).getCode());
            verify(es, times(2)).hybridSearch(anyString(), any(), any(), anyInt(), anyString(), anyString(), anyString());
            verify(session, times(2)).run(anyString(), anyMap());
            verify(items, times(3)).findAll();
            verify(items, times(2)).findAccessibleByDept("A");
        }
    }
}
