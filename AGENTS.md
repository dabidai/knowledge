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

### 1. 导入报错 "目录中未找到支持的文档文件"

**原因**: 扫描目录时没有发现 pdf/docx/ofd 等文档文件，旧版本强制要求文档和 CSV 同时存在。

**解决**: 已修复为只要 CSV **或** 文档存在即可导入（`ImportService.java:134`），拉取最新代码后重建即可。

---

### 2. 导入只显示 2/2 而不是 3/3（缺少 item_with_opinions）

**原因**: CSV 文件名前缀匹配顺序问题。`name.startsWith("item")` 会先匹配到 `item_with_opinions.csv`，将其存入 `csvFiles["item"]` 位置，导致真正的 `item.csv` 被 `putIfAbsent` 跳过。

**解决**: 已修复匹配顺序 —— `item_with_opinions` 必须在 `item` 之前判断（`ImportService.java:120`）。

---

### 3. 目录浏览报错 "class java.util.LinkedHashMap cannot be cast to class java.util.List"

**原因**: `ImportController.browseDir()` 方法声明返回 `List<Map>`，但实际返回的是 `Map`，强制类型转换 `(List)(Object)result` 运行时抛 ClassCastException。

**解决**: 已修复返回类型为 `ApiResponse<Map<String, Object>>`，去掉强制转换。

---

### 4. 前端页面加载失败 "Failed to fetch dynamically imported module"

**原因**: `ImportPage.vue` 导入了不存在的图标 `FolderChecked`（应为 `CircleCheck`），Vite 编译失败。

**解决**: 已替换为 `CircleCheck`。

---

### 5. Vue 模板编译报错 "onclosetag"

**原因**: `<div class="import-page">` 缺少闭合标签 `</div>`，Vue 解析器到 `</template>` 时报错。

**解决**: 已补回 `</div>`。

---

### 6. 导入报错 "could not execute statement [value too long for type character varying(64)]"

**原因**: `items` 表的 `item_id` 字段定义为 `varchar(64)`，CSV 中某些 ID 超出长度。

**解决**: 加大字段长度并编译部署：
```bash
docker exec kb-postgres psql -U kb_user -d knowledge -c \
  "ALTER TABLE items ALTER COLUMN item_id TYPE varchar(255);"
cd ~/llm/docker/anythingllm/knowledge/backend && mvn package -DskipTests -q
kill $(ss -tlnp | grep 8080 | grep -oP 'pid=\K\d+') 2>/dev/null
nohup java -jar target/knowledge-base-0.1.0.jar > app.log 2>&1 &
```

---

### 7. ES 413 Request Entity Too Large

**原因**: OFD 文件分块过多，单次 bulk 请求超过 ES 100MB 限制。

**解决**: 分块批处理（每批 200 条） + ES 容器设置 `http.max_content_length=500mb`。

---

### 8. ZIP Bomb 检测误报

**原因**: POI 默认 `minInflateRatio=0.01`，含 EMF/WMF 图片的 docx 文件压缩率超低被误判。

**解决**: 静态初始化 `ZipSecureFile.setMinInflateRatio(0.001)`。

---

### 9. OLE2/OOXML 格式混淆

**原因**: 部分 `.doc` 文件实际是 OOXML 格式，`.docx` 实际是 OLE2 格式。

**解决**: `parseDocOrDocx()` 先按扩展名选解析器，失败后自动切换另一种。

---

### 10. 导入目录不在 IMPORT_ROOT_DIR 范围内

**原因**: `importFromDir` 和 `importFromPath` 会检查路径是否在 `IMPORT_ROOT_DIR` 内。

**解决**: 已移除该限制，用户通过目录浏览器自由选择路径。同时 `browseDir` 改为从根 `/` 开始浏览。

---

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
