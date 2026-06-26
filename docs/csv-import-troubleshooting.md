# CSV 导入字段超长问题排查与修复

## 问题现象

导入 CSV 文件时反复报错：
```
导入失败: could not execute statement [ERROR: value too long for type character varying(N)]
```
N 从 64 → 255 → 20 → 50 一路变化，每次加大字段后换另一个字段报超长。

---

## 排查过程

### 第一步：怀疑字段长度不足（方向错误）

最初报 `varchar(64)`，对应 `item_id` 字段（`@Column(length = 64)`）。加大到 255，又报 `varchar(20)`，对应 `item_type`。再加大，又报 `varchar(50)`，对应 `year`。

**发现**：每次加大一个字段，就换另一个字段报错。这不正常。正常的 CSV 数据中 `item_type` 只应该是"收文/发文"（2-4 字符），不可能超过 20。`year` 只应该是 4 位年份，不可能超 50。说明数据本身被污染了。

### 第二步：用 Python csv 模块诊断（定位根因）

写 Python 脚本遍历 CSV 文件，检查每行列数和字段长度。**Python `csv.reader` 解析完全正常**，没有超长行，没有列数异常。

但同时给 Java 加诊断日志，openCSV 解析却显示大量行：列数 10-36 不等，`year` 字段长度 1000+，`itemType` 字段长度 2000+，字段内容全是拼接出的错乱文本。

**关键矛盾**：同一个 CSV 文件，Python csv 解析正常，Java openCSV 解析错乱。说明**问题不在 CSV 数据本身，而在 openCSV 默认解析器**。

### 第三步：定位 openCSV 解析差异的根因

openCSV 默认使用 `CSVParser`（非 RFC 4180），它有两个与 Python csv 模块不同的行为：

1. **转义字符**：openCSV 默认用 `\`（反斜杠）作为转义符。当字段内容中出现 `\"` 时，openCSV 会将 `"` 视为文本而非字段结束符。但 CSV 原数据中如果存在非标准的引号用法，反斜杠转义规则会导致引号匹配逻辑与 Python 完全不同。

2. **引号处理严格度**：原始 CSV 文件由 Python `csv.writer` 重新生成过，遵循 RFC 4180 标准（用 `""` 双重引号转义，不使用 `\` 转义）。openCSV 默认解析器的非标准规则恰好与这个格式产生冲突，导致多行字段的引号无法正确闭合。

**具体例子**：
CSV 中有一个标题字段跨 4 行，其中包含中文书名号内的 ASCII 双引号 `《"文物考古调查勘探"审批》`。openCSV 默认解析器遇到这个 `"` 时按自己的规则匹配引号对，导致字段边界错乱，后续数据行的字段全部偏移——A 行的标题末尾拼到 B 行的 `item_id` 位置，B 行的所有字段顺移，最终某个字段长度超出数据库限制。

### 第四步：尝试 CSV 文件修复（治标不治本）

用 Python `csv.reader` → `csv.writer` 循环重写所有 CSV 文件，希望统一为严格 RFC 4180 格式。重写后 Python 仍然能正确解析，但 **openCSV 默认解析器依然报错**——因为 openCSV 默认解析器和 Python csv 模块的底层解析规则根本不同，文件再怎么重写，只要 openCSV 还在用非标准规则，差异就存在。

---

## 最终解决方案

### 根本原因

openCSV 默认 `CSVParser` 使用 `\` 作为转义字符（非 RFC 4180 标准），而 Python `csv` 模块使用 RFC 4180 标准（`""` 转义，无转义字符）。同一份 RFC 4180 格式的 CSV 文件被两个解析器理解成不同的数据结构。

### 修复方法

将 openCSV 的解析器从默认 `CSVParser` 替换为 `RFC4180Parser`，使其行为与 Python `csv` 模块完全一致。

**`CsvImportService.java` 改动**：

```java
// 修改前：使用默认 CSVParser（\ 转义，非 RFC 4180）
try (CSVReader reader = new CSVReader(
        new InputStreamReader(new ByteArrayInputStream(raw), Charset.forName(encoding)))) {

// 修改后：使用 RFC4180Parser（和 Python csv 模块相同标准）
try (CSVReader reader = new CSVReaderBuilder(
        new InputStreamReader(new ByteArrayInputStream(raw), Charset.forName(encoding)))
        .withCSVParser(new RFC4180Parser())
        .build()) {
```

增加 import：
```java
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180Parser;
```

### 同时做的辅助修复

1. **扩大数据库字段**（避免合法长数据被截断）：
   - `items` 表：`item_type` 20→255, `year` 10→50, `dept_name` 100→255, `import_batch` 64→255
   - `items` 表：`item_id`、`title`、`category`、`category_no`、`ref_no`、`issuer` 改为 `text`
   - `documents` 表：`file_id` 64→text, `file_name`、`dept_name` 加大
   - `opinions` 表：`signer` 50→text

2. **实体注解同步更新**（`Item.java`、`Document.java`、`Opinion.java`）：
   - 将所有 `@Column(length = N)` 改为 `@Column(columnDefinition = "TEXT")` 或匹配 DB 的 length 值
   - 防止未来 `ddl-auto: update` 建表时字段过小

3. **添加防护逻辑**（`CsvImportService.java`）：
   - 对 `importItemCsv`、`importFileIndexCsv`、`importOpinionsCsv` 增加列数校验
   - 列数不匹配预期的行跳过并记录警告日志（`log.warn("跳过异常行...")`）
   - 此时这些校验不会触发（因为 `RFC4180Parser` 已能正确解析），但作为防御性编程保留

---

## 关键经验

1. **"字段超长"反复在不同字段出现 → 不是长度问题，是解析错误**。正常短数据不可能超长，超长是因为数据串位。

2. **同文件两套工具解析结果不同 → 不是数据问题，是解析器差异**。Python csv 和 Java openCSV 都声称支持 RFC 4180，但默认行为不同。

3. **openCSV 默认解析器 ≠ RFC 4180**。它的默认 `CSVParser` 额外支持反斜杠转义，遇到特殊字符组合时会产生与 Python csv 不同的结果。要真正对齐，必须显式指定 `RFC4180Parser`。

4. **先诊断再改**。如果不加诊断日志盲目加大字段，可能把所有字段都改成 text 仍然解决不了问题（字段内容全是错乱拼接的垃圾数据）。

---

## 受影响文件

| 文件 | 改动说明 |
|------|---------|
| `CsvImportService.java` | openCSV 替换为 RFC4180Parser + 列数校验防御性代码 |
| `Item.java` | 所有字段 length 扩大或改为 TEXT，对齐 DB |
| `Document.java` | `fileId` 改为 TEXT |
| `Opinion.java` | `signer` 改为 TEXT |
| 数据库 `items`/`documents`/`opinions` | 多个字段改为 text/varchar(255) |
