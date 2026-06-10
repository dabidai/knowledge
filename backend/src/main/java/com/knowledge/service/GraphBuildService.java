package com.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 知识图谱构建服务
 * 在 CSV 导入和文档解析过程中同步创建图节点和关系
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphBuildService {

    private final Driver neo4jDriver;

    // ==================== 约束初始化 ====================

    /** 确保 Neo4j 中有所需的约束和索引（首次启动时调用） */
    public void ensureConstraints() {
        try (Session session = neo4jDriver.session()) {
            session.run("CREATE CONSTRAINT item_id IF NOT EXISTS FOR (i:Item) REQUIRE i.item_id IS UNIQUE");
            session.run("CREATE CONSTRAINT doc_id IF NOT EXISTS FOR (d:Document) REQUIRE d.file_id IS UNIQUE");
            session.run("CREATE CONSTRAINT dept_name IF NOT EXISTS FOR (d:Department) REQUIRE d.name IS UNIQUE");
            session.run("CREATE CONSTRAINT user_name IF NOT EXISTS FOR (u:User) REQUIRE u.username IS UNIQUE");
            log.info("Neo4j 约束初始化完成");
        } catch (Exception e) {
            log.warn("Neo4j 约束初始化失败（可能已存在）: {}", e.getMessage());
        }
    }

    // ==================== 部门 ====================

    /** 创建部门节点 */
    public void createDepartment(String name) {
        try (Session session = neo4jDriver.session()) {
            session.run("MERGE (d:Department {name: $name})", Map.of("name", name));
            log.debug("Neo4j 部门节点: {}", name);
        } catch (Exception e) {
            log.error("Neo4j 创建部门失败: {}", name, e);
        }
    }

    // ==================== 事项 ====================

    /** 创建事项节点 */
    public void createItem(String itemId, String title, String category,
                           String deptName, boolean isPublic) {
        try (Session session = neo4jDriver.session()) {
            session.run("""
                MERGE (i:Item {item_id: $itemId})
                SET i.title = $title, i.category = $category,
                    i.dept_name = $deptName, i.is_public = $isPublic
                """, Map.of("itemId", itemId, "title", title,
                        "category", category, "deptName", deptName, "isPublic", isPublic));
            log.debug("Neo4j 事项节点: {}", itemId);
        } catch (Exception e) {
            log.error("Neo4j 创建事项失败: {}", itemId, e);
        }
    }

    /** 创建 部门-[:OWNS]->事项 关系 */
    public void linkDeptOwnsItem(String deptName, String itemId) {
        try (Session session = neo4jDriver.session()) {
            session.run("""
                MATCH (d:Department {name: $deptName})
                MATCH (i:Item {item_id: $itemId})
                MERGE (d)-[:OWNS]->(i)
                """, Map.of("deptName", deptName, "itemId", itemId));
        } catch (Exception e) {
            log.error("Neo4j 创建 OWNS 关系失败: {} -> {}", deptName, itemId, e);
        }
    }

    // ==================== 文档 ====================

    /** 创建文档节点 */
    public void createDocument(String fileId, String fileName, String status) {
        try (Session session = neo4jDriver.session()) {
            session.run("""
                MERGE (d:Document {file_id: $fileId})
                SET d.file_name = $fileName, d.status = $status
                """, Map.of("fileId", fileId, "fileName", fileName, "status", status));
            log.debug("Neo4j 文档节点: {}", fileId);
        } catch (Exception e) {
            log.error("Neo4j 创建文档失败: {}", fileId, e);
        }
    }

    /** 创建 事项-[:CONTAINS]->文档 关系 */
    public void linkItemContainsDoc(String itemId, String fileId) {
        try (Session session = neo4jDriver.session()) {
            session.run("""
                MATCH (i:Item {item_id: $itemId})
                MATCH (d:Document {file_id: $fileId})
                MERGE (i)-[:CONTAINS]->(d)
                """, Map.of("itemId", itemId, "fileId", fileId));
        } catch (Exception e) {
            log.error("Neo4j 创建 CONTAINS 关系失败: {} -> {}", itemId, fileId, e);
        }
    }

    // ==================== 签阅 ====================

    /** 创建签阅节点 + 关系 */
    public void createOpinion(String itemId, String signer, String content,
                              LocalDateTime signTime) {
        try (Session session = neo4jDriver.session()) {
            // 创建签阅节点
            session.run("""
                MATCH (i:Item {item_id: $itemId})
                CREATE (o:Opinion {
                    signer: $signer, content: $content, sign_time: toString($signTime)
                })
                CREATE (i)-[:HAS_OPINION]->(o)
                """, Map.of("itemId", itemId, "signer", signer,
                        "content", content != null ? content : "",
                        "signTime", signTime != null ? signTime.toString() : ""));

            // 尝试关联用户
            session.run("""
                MATCH (u:User {username: $username})
                MATCH (o:Opinion {signer: $username, sign_time: toString($signTime)})
                MERGE (u)-[:SIGNED]->(o)
                """, Map.of("username", signer, "signTime",
                        signTime != null ? signTime.toString() : ""));
        } catch (Exception e) {
            log.error("Neo4j 创建签阅失败: {}", itemId, e);
        }
    }

    // ==================== 用户 ====================

    /** 创建用户节点 + 部门归属 */
    public void createUser(String username, String role, String deptName) {
        try (Session session = neo4jDriver.session()) {
            session.run("""
                MERGE (u:User {username: $username})
                SET u.role = $role
                WITH u
                MATCH (d:Department {name: $deptName})
                MERGE (u)-[:MEMBER_OF]->(d)
                """, Map.of("username", username, "role", role, "deptName", deptName));
            log.debug("Neo4j 用户节点: {} ({} -> {})", username, role, deptName);
        } catch (Exception e) {
            log.error("Neo4j 创建用户失败: {}", username, e);
        }
    }

    // ==================== 交叉引用 ====================

    /**
     * 创建文档间的交叉引用关系
     * @param sourceFileId  引用方文件ID
     * @param targetFileId  被引用方文件ID
     * @param refType       引用类型 (explicit/implicit)
     * @param context       引用上下文（如"参见2021通1234号"）
     */
    public void createCrossReference(String sourceFileId, String targetFileId,
                                     String refType, String context) {
        try (Session session = neo4jDriver.session()) {
            session.run("""
                MATCH (d1:Document {file_id: $sourceId})
                MATCH (d2:Document {file_id: $targetId})
                MERGE (d1)-[:REFERENCES {type: $refType, context: $context}]->(d2)
                """, Map.of("sourceId", sourceFileId, "targetId", targetFileId,
                        "refType", refType, "context", context));
            log.debug("Neo4j 交叉引用: {} -[:REFERENCES]-> {}", sourceFileId, targetFileId);
        } catch (Exception e) {
            log.error("Neo4j 创建引用关系失败: {} -> {}", sourceFileId, targetFileId, e);
        }
    }

    /**
     * 批量检测并创建交叉引用
     * 扫描所有文档内容，查找对其他事项分类编号的引用
     */
    public void detectCrossReferences(List<DocRef> allDocs) {
        log.info("开始交叉引用检测，共 {} 个文档", allDocs.size());
        int refCount = 0;

        for (DocRef source : allDocs) {
            if (source.content() == null || source.content().isEmpty()) continue;

            for (DocRef target : allDocs) {
                if (source.fileId().equals(target.fileId())) continue;
                if (target.categoryNo() == null || target.categoryNo().isEmpty()) continue;

                // 检查 source 的文本内容是否包含 target 的分类编号
                if (source.content().contains(target.categoryNo())) {
                    createCrossReference(source.fileId(), target.fileId(),
                            "explicit", "引用分类编号: " + target.categoryNo());
                    refCount++;
                }
            }
        }

        log.info("交叉引用检测完成: {} 条引用关系", refCount);
    }

    /** 交叉引用检测用的文档摘要 */
    public record DocRef(String fileId, String content, String categoryNo) {}
}
