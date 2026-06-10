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
import java.util.stream.Collectors;

/** 知识图谱控制器 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphController {

    private final Driver neo4jDriver;

    /**
     * 查询知识图谱数据
     * @param itemId 可选，查询指定事项的图谱；不传则返回全局概览
     */
    @GetMapping
    public ApiResponse<GraphData> query(
            @RequestParam(required = false) String itemId,
            @AuthenticationPrincipal User user) {

        try (Session session = neo4jDriver.session()) {
            if (itemId != null && !itemId.isBlank()) {
                // 查询特定事项的完整脉络
                return ApiResponse.ok(queryItemGraph(session, itemId));
            } else {
                // 全局概览：部门 → 事项
                return ApiResponse.ok(queryOverview(session, user));
            }
        } catch (Exception e) {
            log.error("图谱查询失败", e);
            return ApiResponse.error(500, "图谱查询失败: " + e.getMessage());
        }
    }

    /** 查询全局概览 */
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

            // 部门节点
            String deptId = "dept:" + dept;
            if (nodeIds.add(deptId)) {
                nodes.add(GraphNode.builder()
                        .id(deptId).label(dept).type("department").build());
            }

            // 事项节点
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

    /** 查询特定事项的完整脉络 */
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

            // 文档节点
            var docs = r.get("docs").asList(org.neo4j.driver.Value::asNode);
            for (var docNode : docs) {
                String docId = docNode.get("file_id").asString();
                String docName = docNode.get("file_name").asString("");
                nodes.add(GraphNode.builder()
                        .id(docId).label(docName).type("document").build());
                edges.add(GraphEdge.builder()
                        .source(iId).target(docId).label("CONTAINS").build());
            }

            // 签阅节点
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

            // 用户节点
            var users = r.get("users").asList(org.neo4j.driver.Value::asNode);
            for (var userNode : users) {
                String username = userNode.get("username").asString("");
                String userId = "user:" + username;
                if (nodes.stream().noneMatch(n -> n.getId().equals(userId))) {
                    nodes.add(GraphNode.builder()
                            .id(userId).label(username).type("user").build());
                }
                // 找关联的签阅
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

    // -- DTO --

    @Data
    @Builder
    public static class GraphData {
        private List<GraphNode> nodes;
        private List<GraphEdge> edges;
    }

    @Data
    @Builder
    public static class GraphNode {
        private String id;
        private String label;
        private String type;
        private Map<String, Object> properties;
    }

    @Data
    @Builder
    public static class GraphEdge {
        private String source;
        private String target;
        private String label;
    }
}
