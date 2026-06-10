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
 * Neo4j 知识图谱构建服务 —— 封装所有 Cypher 图操作。
 * 在 CSV 导入和文档解析过程中同步创建图节点和关系，
 * 导入完成后执行交叉引用检测。
 *
 * <p>图模型：
 * <pre>
 *   (:Department) -[:OWNS]-> (:Item) -[:CONTAINS]-> (:Document)
 *   (:Item) -[:HAS_OPINION]-> (:Opinion) <-[:SIGNED]- (:User)
 *   (:User) -[:MEMBER_OF]-> (:Department)
 *   (:Document) -[:REFERENCES]-> (:Document)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphBuildService {

    /** Neo4j 驱动（Bolt 协议连接） */
    private final Driver neo4jDriver;

    // ==================== 约束初始化 ====================

    /**
     * 确保 Neo4j 中存在所需的唯一性约束和索引。
     * 首次启动时调用，重复调用幂等。
     */
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

    /**
     * 创建部门节点，已存在则忽略。
     *
     * @param name 部门名称
     */
    public void createDepartment(String name) {
        try (Session session = neo4jDriver.session()) {
            session.run("MERGE (d:Department {name: $name})", Map.of("name", name));
            log.debug("Neo4j 部门节点: {}", name);
        } catch (Exception e) {
            log.error("Neo4j 创建部门失败: {}", name, e);
        }
    }

    // ==================== 事项 ====================

    /**
     * 创建事项节点（MERGE 保证幂等），并设置标题、分类等属性。
     *
     * @param itemId   事项ID（来自 CSV 的原始 ID）
     * @param title    事项标题
     * @param category 事项分类（如 "通知"）
     * @param deptName 所属部门名称
     * @param isPublic 是否公共区文档
     */
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

    /**
     * 创建 部门 → 事项 的归属关系。
     *
     * @param deptName 部门名称
     * @param itemId   事项ID
     */
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

    /**
     * 创建文档节点，已存在则更新文件名和状态。
     *
     * @param fileId   文件ID（来自 CSV 的原始 ID 或 UUID）
     * @param fileName 文件名 / 路径
     * @param status   状态：expected / matched / orphan
     */
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

    /**
     * 创建 事项 → 文档 的包含关系。
     *
     * @param itemId 事项ID
     * @param fileId 文件ID
     */
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

    /**
     * 创建签阅节点，并建立 事项→签阅 和 用户→签阅 两条关系。
     * 如果签阅人未在 user 表中，仅保留签阅节点文本，不创建 User 关联。
     *
     * @param itemId   事项ID
     * @param signer   签阅人姓名
     * @param content  签阅意见内容
     * @param signTime 签阅时间
     */
    public void createOpinion(String itemId, String signer, String content,
                              LocalDateTime signTime) {
        try (Session session = neo4jDriver.session()) {
            String timeStr = signTime != null ? signTime.toString() : "";
            String c = content != null ? content : "";

            // 创建签阅节点 + 事项关联
            session.run("""
                MATCH (i:Item {item_id: $itemId})
                CREATE (o:Opinion {
                    signer: $signer, content: $content, sign_time: $signTime
                })
                CREATE (i)-[:HAS_OPINION]->(o)
                """, Map.of("itemId", itemId, "signer", signer,
                        "content", c, "signTime", timeStr));

            // 尝试关联用户节点（签阅人可能在 user.csv 中已导入）
            session.run("""
                MATCH (u:User {username: $username})
                MATCH (o:Opinion {signer: $username, sign_time: $signTime})
                MERGE (u)-[:SIGNED]->(o)
                """, Map.of("username", signer, "signTime", timeStr));
        } catch (Exception e) {
            log.error("Neo4j 创建签阅失败: {}", itemId, e);
        }
    }

    // ==================== 用户 ====================

    /**
     * 创建用户节点并关联到所属部门。
     *
     * @param username 用户名
     * @param role     角色（admin / default）
     * @param deptName 所属部门名称
     */
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
     * 创建文档间的交叉引用关系。
     *
     * @param sourceFileId 引用方文件ID
     * @param targetFileId 被引用方文件ID
     * @param refType      引用类型（explicit：基于分类编号匹配；implicit：基于语义匹配）
     * @param context      引用上下文（如 "引用分类编号: 2021通1234"）
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
     * 批量检测并创建文档间的交叉引用关系。
     * 遍历所有文档，检查其文本内容是否包含其他文档的分类编号，
     * 匹配到则创建 REFERENCES 关系。
     *
     * @param allDocs 文档摘要列表，包含文件ID、文本内容、分类编号
     */
    public void detectCrossReferences(List<DocRef> allDocs) {
        log.info("开始交叉引用检测，共 {} 个文档", allDocs.size());
        int refCount = 0;

        for (DocRef source : allDocs) {
            if (source.content() == null || source.content().isEmpty()) continue;

            for (DocRef target : allDocs) {
                if (source.fileId().equals(target.fileId())) continue;
                if (target.categoryNo() == null || target.categoryNo().isEmpty()) continue;

                if (source.content().contains(target.categoryNo())) {
                    createCrossReference(source.fileId(), target.fileId(),
                            "explicit", "引用分类编号: " + target.categoryNo());
                    refCount++;
                }
            }
        }

        log.info("交叉引用检测完成: {} 条引用关系", refCount);
    }

    /**
     * 交叉引用检测用的文档摘要。
     *
     * @param fileId     文件ID
     * @param content    文档文本内容（用于匹配引用）
     * @param categoryNo 事项分类编号（被其他文档引用时匹配）
     */
    public record DocRef(String fileId, String content, String categoryNo) {}
}
