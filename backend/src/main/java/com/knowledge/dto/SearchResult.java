package com.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/** 搜索结果 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    /** RAG 生成的答案 */
    private String answer;
    /** 引用来源 */
    private List<SourceDoc> sources;
    /** 关联事项 */
    private List<RelatedItem> relatedItems;
    /** 知识图谱数据 (Neo4j 返回) */
    private Object knowledgeGraph;
    /** 向量服务故障时使用关键词检索，供前端明确提示。 */
    private boolean degraded;
    private String warning;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceDoc {
        private String fileId;
        private String fileName;
        private String snippet;       // 高亮片段
        private String downloadUrl;   // MinIO 预签名下载链接
        private String deptName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedItem {
        private String itemId;
        private String title;
        private String categoryNo;
    }
}
