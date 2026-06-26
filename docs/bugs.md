# Bug 记录

本项目开发与运维中遇到的 Bug，按时间排序。每个条目记录现象、原因、解决方案，方便复盘。

---

### 1. 导入报错 "目录中未找到支持的文档文件"

- **日期**: 2026-06
- **标签**: `import` `csv` `后端`

**原因**: 扫描目录时没有发现 pdf/docx/ofd 等文档文件，旧版本强制要求文档和 CSV 同时存在。

**解决**: 已修复为只要 CSV **或** 文档存在即可导入（`ImportService.java:134`），拉取最新代码后重建即可。

---

### 2. 导入只显示 2/2 而不是 3/3（缺少 item_with_opinions）

- **日期**: 2026-06
- **标签**: `import` `csv` `后端`

**原因**: CSV 文件名前缀匹配顺序问题。`name.startsWith("item")` 会先匹配到 `item_with_opinions.csv`，将其存入 `csvFiles["item"]` 位置，导致真正的 `item.csv` 被 `putIfAbsent` 跳过。

**解决**: 已修复匹配顺序 —— `item_with_opinions` 必须在 `item` 之前判断（`ImportService.java:120`）。

---

### 3. 目录浏览报错 "class java.util.LinkedHashMap cannot be cast to class java.util.List"

- **日期**: 2026-06
- **标签**: `import` `后端` `类型转换`

**原因**: `ImportController.browseDir()` 方法声明返回 `List<Map>`，但实际返回的是 `Map`，强制类型转换 `(List)(Object)result` 运行时抛 ClassCastException。

**解决**: 已修复返回类型为 `ApiResponse<Map<String, Object>>`，去掉强制转换。

---

### 4. 前端页面加载失败 "Failed to fetch dynamically imported module"

- **日期**: 2026-06
- **标签**: `前端` `导入` `图标`

**原因**: `ImportPage.vue` 导入了不存在的图标 `FolderChecked`（应为 `CircleCheck`），Vite 编译失败。

**解决**: 已替换为 `CircleCheck`。

---

### 5. Vue 模板编译报错 "onclosetag"

- **日期**: 2026-06
- **标签**: `前端` `模板` `HTML`

**原因**: `<div class="import-page">` 缺少闭合标签 `</div>`，Vue 解析器到 `</template>` 时报错。

**解决**: 已补回 `</div>`。

---

### 6. 导入报错 "could not execute statement [value too long for type character varying(64)]"

- **日期**: 2026-06
- **标签**: `import` `csv` `数据库` `字段长度`

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

- **日期**: 2026-06
- **标签**: `elasticsearch` `ofd` `文档解析`

**原因**: OFD 文件分块过多，单次 bulk 请求超过 ES 100MB 限制。

**解决**: 分块批处理（每批 200 条） + ES 容器设置 `http.max_content_length=500mb`。

---

### 8. ZIP Bomb 检测误报

- **日期**: 2026-06
- **标签**: `poi` `docx` `文档解析`

**原因**: POI 默认 `minInflateRatio=0.01`，含 EMF/WMF 图片的 docx 文件压缩率超低被误判。

**解决**: 静态初始化 `ZipSecureFile.setMinInflateRatio(0.001)`。

---

### 9. OLE2/OOXML 格式混淆

- **日期**: 2026-06
- **标签**: `poi` `doc` `docx` `文档解析`

**原因**: 部分 `.doc` 文件实际是 OOXML 格式，`.docx` 实际是 OLE2 格式。

**解决**: `parseDocOrDocx()` 先按扩展名选解析器，失败后自动切换另一种。

---

### 10. 导入目录不在 IMPORT_ROOT_DIR 范围内

- **日期**: 2026-06
- **标签**: `import` `后端` `权限`

**原因**: `importFromDir` 和 `importFromPath` 会检查路径是否在 `IMPORT_ROOT_DIR` 内。

**解决**: 已移除该限制，用户通过目录浏览器自由选择路径。同时 `browseDir` 改为从根 `/` 开始浏览。

---

### 11. 导入反复报 "value too long"，在不同字段间轮换

- **日期**: 2026-06
- **标签**: `import` `csv` `opencsv` `解析器` `重点`
- **详见**: [csv-import-troubleshooting.md](csv-import-troubleshooting.md)

**现象**：导入 `meeting/内部工作批示单` 目录的 CSV 文件时，反复报 `value too long for type character varying(N)`，N 从 64 → 255 → 20 → 50 一路变化，每次加大字段后换另一个字段报超长。

**原因**: openCSV 默认解析器（非 RFC 4180）与 Python csv 模块行为不一致。CSV 文件中多行标题字段含未转义 ASCII 双引号，openCSV 默认解析器用 `\` 作转义符，引号匹配逻辑与 RFC 4180 标准不同，导致字段边界错乱、数据跨行串位，最终某个字段长度超出数据库限制。每次加大出错的字段后，错误转移到另一个字段。

**解决**: `CsvImportService.java` 中将 openCSV 解析器替换为 `RFC4180Parser`，与 Python csv 模块使用相同的 RFC 4180 标准。

---

### 12. 导入报 "value too long for type character varying(500)" — 非解析异常

- **日期**: 2026-06
- **标签**: `import` `csv` `数据库` `字段长度` `receive`
- **详见**: [item-fields-text-migration.md](item-fields-text-migration.md)

**现象**：导入 `receive` 目录 CSV 时，报 `varchar(500)` 超长。诊断发现三个 CSV 列数异常行均为 0，`字号` 字段最长 2044 字符，是单纯长度不够而非解析异常。

**解决**: 将 `items` 表 `category`、`category_no`、`ref_no`、`issuer` 从 varchar(500) 改为 TEXT，`Item.java` 同步更新注解。

**诊断方法**: 用 Python csv 模块检查源文件各列最大长度和列数异常行数，两者结合可区分"解析异常"和"单纯长度不够"。

**与条目 11 的对比**：

| 维度 | 条目 11（解析异常） | 条目 12（长度不够） |
|------|------|------|
| 根因 | openCSV 默认解析器用 `\` 转义，数据串位 | varchar(500) 无法容纳合法长数据 |
| 列数异常行 | 大量（10-36 列不等） | 0 |
| 错误轮换 | 每次加大字段后换另一个字段报错 | 只报 varchar(500)，对应 ref_no |
| 解决 | 替换解析器为 `RFC4180Parser` | 字段从 varchar(500) 改为 TEXT |
