package com.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/** Elasticsearch 搜索服务 —— 索引管理 + BM25/KNN 混合检索 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchService {

    private final ElasticsearchClient esClient;

    @Value("${elasticsearch.index-name}")
    private String indexName;

    /** 索引一条文档（含向量） */
    public void indexDocument(DocIndex doc) throws IOException {
        esClient.index(IndexRequest.of(r -> r
                .index(indexName)
                .id(doc.docId + "_" + doc.chunkIndex)
                .document(doc)
        ));
    }

    /** 批量索引 */
    public void bulkIndex(List<DocIndex> docs) throws IOException {
        BulkRequest.Builder br = new BulkRequest.Builder();
        for (DocIndex doc : docs) {
            br.operations(op -> op
                    .index(idx -> idx
                            .index(indexName)
                            .id(doc.docId + "_" + doc.chunkIndex)
                            .document(doc)
                    )
            );
        }
        BulkResponse resp = esClient.bulk(br.build());
        if (resp.errors()) {
            log.error("ES 批量索引有错误: {}", resp.items().size());
        }
        log.info("ES 批量索引完成: {} 条", docs.size());
    }

    /**
     * 混合检索：BM25 关键词 + KNN 向量
     * @param queryText   查询文本
     * @param queryVector 查询向量（可为 null，仅做 BM25）
     * @param deptName    用户所在部门（用于权限过滤）
     * @param topK        返回结果数
     */
    public List<DocIndex> hybridSearch(String queryText, float[] queryVector,
                                        String deptName, int topK) throws IOException {

        // BM25 子查询
        Query bm25Query = Query.of(q -> q
                .bool(BoolQuery.of(b -> {
                    b.must(m -> m.match(ma -> ma.field("content").query(queryText)));
                    // 权限过滤：本部门 OR 公共区
                    b.filter(f -> f.bool(bf -> bf
                            .should(s -> s.term(t -> t.field("deptName").value(deptName)))
                            .should(s -> s.term(t -> t.field("isPublic").value(true)))
                    ));
                    return b;
                }))
        );

        if (queryVector != null && queryVector.length > 0) {
            // 有向量：BM25 + KNN 混合，用 RRF 融合
            SearchResponse<DocIndex> response = esClient.search(SearchRequest.of(s -> s
                    .index(indexName)
                    .query(bm25Query)
                    .knn(KnnSearch.of(k -> k
                            .field("contentVector")
                            .queryVector(Arrays.asList(toFloatList(queryVector)))
                            .k(topK * 2)
                            .numCandidates(topK * 5)
                    ))
                    .size(topK)
                    .highlight(h -> h
                            .fields("content", hf -> hf
                                    .fragmentSize(150)
                                    .numberOfFragments(2)
                                    .preTags("<em>")
                                    .postTags("</em>")
                            )
                    )
            ), DocIndex.class);

            return extractResults(response);
        } else {
            // 纯 BM25 检索
            SearchResponse<DocIndex> response = esClient.search(SearchRequest.of(s -> s
                    .index(indexName)
                    .query(bm25Query)
                    .size(topK)
                    .highlight(h -> h
                            .fields("content", hf -> hf
                                    .fragmentSize(150)
                                    .numberOfFragments(2)
                                    .preTags("<em>")
                                    .postTags("</em>")
                            )
                    )
            ), DocIndex.class);

            return extractResults(response);
        }
    }

    private List<DocIndex> extractResults(SearchResponse<DocIndex> response) {
        List<DocIndex> results = new ArrayList<>();
        for (Hit<DocIndex> hit : response.hits().hits()) {
            DocIndex doc = hit.source();
            if (doc != null) {
                // 合并高亮片段
                if (hit.highlight() != null && hit.highlight().containsKey("content")) {
                    String snippet = String.join(" ... ",
                            hit.highlight().get("content"));
                    doc.snippet = snippet;
                }
                results.add(doc);
            }
        }
        return results;
    }

    /** 确保索引存在并创建 mapping */
    public void ensureIndex() throws IOException {
        ExistsRequest exists = ExistsRequest.of(r -> r.index(indexName));
        if (esClient.indices().exists(exists).value()) {
            return;
        }

        esClient.indices().create(CreateIndexRequest.of(c -> c
                .index(indexName)
                .mappings(m -> m
                        .properties("docId", p -> p.keyword(k -> k))
                        .properties("fileName", p -> p.keyword(k -> k))
                        .properties("deptName", p -> p.keyword(k -> k))
                        .properties("isPublic", p -> p.boolean_(k -> k))
                        .properties("itemTitle", p -> p.text(t -> t.analyzer("ik_max_word")))
                        .properties("itemCategory", p -> p.keyword(k -> k))
                        .properties("itemYear", p -> p.integer(k -> k))
                        .properties("content", p -> p.text(t -> t.analyzer("ik_max_word")))
                        .properties("contentVector", p -> p
                                .denseVector(dv -> dv.dims(1024)))
                        .properties("minioPath", p -> p.keyword(k -> k))
                        .properties("chunkIndex", p -> p.integer(k -> k))
                )
        ));
        log.info("ES 索引创建成功: {}", indexName);
    }

    private Float[] toFloatList(float[] arr) {
        Float[] result = new Float[arr.length];
        for (int i = 0; i < arr.length; i++) result[i] = arr[i];
        return result;
    }

    /** ES 文档索引模型 */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DocIndex {
        private String docId;
        private String fileName;
        private String deptName;
        private Boolean isPublic;
        private String itemTitle;
        private String itemCategory;
        private Integer itemYear;
        private String content;
        private float[] contentVector;  // 1024 维
        private String minioPath;
        private Integer chunkIndex;
        // 搜索结果回填：高亮片段
        private String snippet;
    }
}
