# 项目背景与运维手册

## 部署信息

- **服务器**: Linux (Ubuntu)
- **后端**: Spring Boot 3.3.5, 端口 8080
- **前端**: Vue 3 + Vite, 端口 3000
- **AI 服务**: FastAPI embedding 服务
- **Docker 容器**: PostgreSQL 16, Redis 7, Elasticsearch 8.15 (IK 分词), MinIO, Neo4j 5

### 项目路径

```
后端: ~/llm/docker/anythingllm/knowledge/backend
前端: ~/llm/docker/anythingllm/knowledge/frontend
数据: ~/llm/docker/anythingllm/knowledge/input
```

## 常用运维命令

### 启动/重启

```bash
# 后端编译 + 启动
cd ~/llm/docker/anythingllm/knowledge/backend
mvn package -DskipTests -q
kill $(ss -tlnp | grep 8080 | grep -oP 'pid=\K\d+') 2>/dev/null
nohup java -jar target/knowledge-base-0.1.0.jar > app.log 2>&1 &
sleep 3 && ss -tlnp | grep 8080

# 前端启动
cd ~/llm/docker/anythingllm/knowledge/frontend
kill $(ss -tlnp | grep 3000 | grep -oP 'pid=\K\d+') 2>/dev/null
nohup npm run dev -- --host > frontend.log 2>&1 &
```

### 查看状态

```bash
ss -tlnp | grep -E '8080|3000'    # 端口监听状态
tail -50 app.log                   # 后端日志
tail -50 frontend.log              # 前端日志
```

### 拉取更新

```bash
cd ~/llm/docker/anythingllm/knowledge && git pull gitee master
```

### 数据查看

```bash
# PostgreSQL
docker exec kb-postgres psql -U kb_user -d knowledge -c \
  "SELECT status, COUNT(*) FROM documents GROUP BY status;"
docker exec kb-postgres psql -U kb_user -d knowledge -c \
  "SELECT COUNT(*) FROM items;"

# Elasticsearch
curl -s http://localhost:9200/_cat/indices?v

# Neo4j
docker exec kb-neo4j cypher-shell -u neo4j -p neo4j_pass \
  "MATCH (n) RETURN count(n) AS nodes;"
```

### 数据清理

```bash
# PostgreSQL 保留用户/部门, 清除导入数据
docker exec kb-postgres psql -U kb_user -d knowledge -c \
  "TRUNCATE documents, items, opinions, import_tasks RESTART IDENTITY CASCADE;"

# ES 索引删除（重启后端自动重建）
curl -X DELETE http://localhost:9200/doc_index

# Neo4j 清空
docker exec kb-neo4j cypher-shell -u neo4j -p neo4j_pass \
  "MATCH (n) DETACH DELETE n;"

# MinIO 文件删除
docker exec kb-minio sh -c "rm -rf /data/knowledge-md/markdown && mkdir -p /data/knowledge-md/markdown"
docker exec kb-minio sh -c "rm -rf /data/knowledge-docs/public && mkdir -p /data/knowledge-docs/public"
```

---

## 常见错误与解决

所有 Bug 记录已迁移至 [docs/bugs.md](docs/bugs.md)，每个条目含现象、原因、解决方案。

## 导入流程说明

### 分开导入 CSV 和文档

CSV 和文档文件可以分开导入，系统会自动匹配：

1. **先导 CSV** → 创建 `status = "expected"` 的记录
2. **后导文档** → 按文件名匹配 `expected` 记录，匹配成功则变 `matched`

注意事项：
- 文件名必须匹配（模糊匹配，不含扩展名部分）
- 导入是幂等的，重复导入不会产生重复数据（按 ID 查，存在则更新）
- 支持多次导入不同目录，数据追加入库

### 数据模型关系

```
item (事项) ←── document (文档/文件映射) ──→ ofd/pdf 实际文件
  ↑                       ↑
  └── opinions (签阅意见)  └── minio 存储解析后的 markdown
```
