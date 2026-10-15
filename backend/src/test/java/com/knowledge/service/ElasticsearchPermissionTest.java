package com.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.knowledge.security.AccessScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.function.Function;
import co.elastic.clients.util.ObjectBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElasticsearchPermissionTest {
    @Test void bothRetrievalBranchesHaveIdenticalMandatoryFilters() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));
        when(client.search(any(SearchRequest.class), eq(ElasticsearchService.DocIndex.class))).thenAnswer(call -> {
            SearchRequest request = call.getArgument(0);
            assertEquals(request.query().bool().filter(), request.knn().get(0).filter());
            var acl = request.knn().get(0).filter().get(0).bool();
            assertEquals("1", acl.minimumShouldMatch());
            assertEquals(2, acl.should().size());
            assertEquals("A", acl.should().get(1).term().value().stringValue());
            assertEquals(3, request.knn().get(0).filter().size()); // ACL + category + year
            return SearchResponse.of(r -> r.took(0).timedOut(false)
                    .shards(s -> s.total(1).successful(1).failed(0)).hits(h -> h.hits(java.util.List.of())));
        });
        var service = new ElasticsearchService(client);
        ReflectionTestUtils.setField(service, "indexName", "test");
        service.hybridSearch("q", new float[]{1}, new AccessScope(false, "A"), 5, "通知", "2026", "");
        verify(client).search(any(SearchRequest.class), eq(ElasticsearchService.DocIndex.class));
    }

    @Test void adminAndDepartmentlessFiltersAreExplicit() {
        assertTrue(ElasticsearchService.accessFilter(new AccessScope(true, "A")).isMatchAll());
        var publicOnly = ElasticsearchService.accessFilter(new AccessScope(false, null)).bool();
        assertEquals("1", publicOnly.minimumShouldMatch());
        assertEquals(1, publicOnly.should().size());
        assertEquals("isPublic", publicOnly.should().get(0).term().field());
    }
}
