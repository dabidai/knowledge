package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.entity.User;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 知识图谱控制器 —— 查询 Neo4j 图数据供前端可视化。
 *
 * <p>两种查询模式：
 * <ul>
 *   <li>全局概览 —— 部门 → 事项（不传 itemId，带缓存）</li>
 *   <li>事项脉络 —— 事项 → 文档 → 签阅 → 用户（传 itemId，不缓存）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphController {

    /** Neo4j 驱动 */
    private final Driver neo4jDriver;

    /**
     * 查询知识图谱数据。
     * 不带 itemId 时返回全局概览（缓存 15 分钟），
     * 带 itemId 时返回该事项的完整脉络（实时查询）。
     *
     * @param itemId 可选，事项ID。传入则查询特定事项脉络
     * @param user   当前认证用户（用于权限过滤）
     * @return 图谱数据（节点列表 + 边列表）
     */
    @GetMapping
    @org.springframework.cache.annotation.Cacheable(
            value = "graphOverview", key = "#user.department.name",
            condition = "#itemId == null || #itemId.isEmpty()")
    public ApiResponse<GraphData> query(
            @RequestParam(required = false) String itemId,
            @AuthenticationPrincipal User user) {

        // 防御：无部门信息的用户无法查询图谱
        if (user.getDepartment() == null) {
            return ApiResponse.error(400, "当前用户未关联部门，无法查询知识图谱");
        }

        try (Session session = neo4jDriver.session()) {
            if (itemId != null && !itemId.isBlank()) {
                return ApiResponse.ok(queryItemGraph(session, itemId));
            } else {
                return ApiResponse.ok(queryOverview(session, user));
            }
        } catch (Exception e) {
            log.error("图谱查询失败", e);
            return ApiResponse.error(500, "图谱查询失败，请确认 Neo4j 服务已启动且已导入文档");
        }
    }

    /**
     * 查询全局概览 —— 部门及其下属事项的统计信息。
     * admin 可查看所有部门，普通用户仅能查看本部门及公共区。
     *
     * @param session Neo4j 会话
     * @param user    当前用户
     * @return 部门→事项 的图数据，最多 100 条
     */
    private GraphData queryOverview(Session session, User user) {
        String deptName = user.getDepartment().getName();
        String cypher;
        Map<String, Object> params = new HashMap<>();

        if ("admin".equals(user.getRole())) {
            cypher = """
                MATCH (d:Department)-[:OWNS]->(i:Item)
                OPTIONAL MATCH (i)-[:CONTAINS]->(doc:Document)
                RETURN d.name AS dept, i.item_id AS itemId, i.title AS title,
                       i.category AS category, COUNT(doc) AS docCount
                ORDER BY d.name, i.title
                LIMIT 100
                """;
        } else {
            cypher = """
                MATCH (d:Department)-[:OWNS]->(i:Item)
                WHERE d.name = $deptName OR i.is_public = true
                OPTIONAL MATCH (i)-[:CONTAINS]->(doc:Document)
                RETURN d.name AS dept, i.item_id AS itemId, i.title AS title,
                       i.category AS category, COUNT(doc) AS docCount
                ORDER BY d.name, i.title
                LIMIT 100
                """;
            params.put("deptName", deptName);
        }

        Result result = session.run(cypher, params);
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();

        for (Record r : result.list()) {
            String dept = r.get("dept").asString();
            String iId = r.get("itemId").asString();
            String title = r.get("title").asString("");
            String category = r.get("category").asString("");
            long docCount = r.get("docCount").asLong(0);

            String deptId = "dept:" + dept;
            if (nodeIds.add(deptId)) {
                nodes.add(GraphNode.builder()
                        .id(deptId).label(dept).type("department").build());
            }

            if (nodeIds.add(iId)) {
                String labelText = title.length() > 20 ? title.substring(0, 20) + "..." : title;
                nodes.add(GraphNode.builder()
                        .id(iId).label(labelText)
                        .type("item")
                        .properties(Map.of("title", title, "category", category, "docCount", docCount))
                        .build());
            }

            edges.add(GraphEdge.builder()
                    .source(deptId).target(iId).label("OWNS").build());
        }

        return GraphData.builder().nodes(nodes).edges(edges).build();
    }

    /**
     * 查询特定事项的完整脉络 —— 包含关联的文档、签阅记录、签阅人。
     *
     * @param session Neo4j 会话
     * @param itemId  事项ID
     * @return 事项为中心的子图数据
     */
    private GraphData queryItemGraph(Session session, String itemId) {
        String cypher = """
            MATCH (i:Item {item_id: $itemId})
            OPTIONAL MATCH (i)-[:CONTAINS]->(doc:Document)
            OPTIONAL MATCH (i)-[:HAS_OPINION]->(o:Opinion)
            OPTIONAL MATCH (u:User)-[:SIGNED]->(o)
            RETURN i, COLLECT(DISTINCT doc) AS docs,
                   COLLECT(DISTINCT o) AS opinions,
                   COLLECT(DISTINCT u) AS users
            """;

        Result result = session.run(cypher, Map.of("itemId", itemId));
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();

        for (Record r : result.list()) {
            var itemNode = r.get("i").asNode();
            String iId = itemNode.get("item_id").asString();
            String iTitle = itemNode.get("title").asString("");

            nodes.add(GraphNode.builder()
                    .id(iId).label(iTitle).type("item")
                    .properties(Map.of("category", itemNode.get("category").asString("")))
                    .build());

            // 文档节点 + CONTAINS 关系
            var docs = r.get("docs").asList(org.neo4j.driver.Value::asNode);
            for (var docNode : docs) {
                String docId = docNode.get("file_id").asString();
                String docName = docNode.get("file_name").asString("");
                nodes.add(GraphNode.builder()
                        .id(docId).label(docName).type("document").build());
                edges.add(GraphEdge.builder()
                        .source(iId).target(docId).label("CONTAINS").build());
            }

            // 签阅节点 + HAS_OPINION 关系
            var opinions = r.get("opinions").asList(org.neo4j.driver.Value::asNode);
            for (var opNode : opinions) {
                String opId = "opinion:" + opNode.elementId();
                String signer = opNode.get("signer").asString("");
                String content = opNode.get("content").asString("");
                nodes.add(GraphNode.builder()
                        .id(opId)
                        .label(signer + ": " + (content.length() > 15 ? content.substring(0, 15) + "..." : content))
                        .type("opinion")
                        .properties(Map.of("signer", signer, "content", content))
                        .build());
                edges.add(GraphEdge.builder()
                        .source(iId).target(opId).label("HAS_OPINION").build());
            }

            // 用户节点 + SIGNED 关系
            var users = r.get("users").asList(org.neo4j.driver.Value::asNode);
            for (var userNode : users) {
                String username = userNode.get("username").asString("");
                String userId = "user:" + username;
                if (nodes.stream().noneMatch(n -> n.getId().equals(userId))) {
                    nodes.add(GraphNode.builder()
                            .id(userId).label(username).type("user").build());
                }
                for (var opNode : opinions) {
                    if (opNode.get("signer").asString("").equals(username)) {
                        edges.add(GraphEdge.builder()
                                .source(userId).target("opinion:" + opNode.elementId())
                                .label("SIGNED").build());
                    }
                }
            }
        }

        return GraphData.builder().nodes(nodes).edges(edges).build();
    }

    // -- 图谱响应 DTO --

    /** 图谱数据，包含节点集合和边集合 */
    @Data
    @Builder
    public static class GraphData {
        /** 节点列表 */
        private List<GraphNode> nodes;
        /** 边列表 */
        private List<GraphEdge> edges;
    }

    /** 图节点 */
    @Data
    @Builder
    public static class GraphNode {
        /** 唯一标识 */
        private String id;
        /** 显示标签 */
        private String label;
        /** 节点类型：department / item / document / opinion / user */
        private String type;
        /** 扩展属性（如标题、分类、文档数等） */
        private Map<String, Object> properties;
    }

    /** 图边 */
    @Data
    @Builder
    public static class GraphEdge {
        /** 源节点ID */
        private String source;
        /** 目标节点ID */
        private String target;
        /** 关系标签：OWNS / CONTAINS / HAS_OPINION / SIGNED / REFERENCES */
        private String label;
    }
}
