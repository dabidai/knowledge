# 导入内存优化方案

## 当前问题

批量导入时存在两个内存隐患：

1. **`docTextMap` 攒所有文档文本** — 最后交叉引用检测时才用，文本一直不释放
2. **单文件 chunk 无上限** — OFD 解析出的垃圾坐标文本可能产生几百个 chunk

## 方案

### 一、docTextMap → 交叉引用时按需读 MinIO

当前逻辑：

```java
Map<String, String> docTextMap = new LinkedHashMap<>();
for (doc : allDocs) {
    String text = processDocument(doc);
    docTextMap.put(fileId, text);  // 攒着
}
// 交叉引用检测
for (doc : allDocs) {
    detect(docTextMap.get(doc.fileId));  // 用到才取
}
```

改为：

```java
for (doc : allDocs) {
    String text = processDocument(doc);
    // 不攒，只记录 fileId
}
// 交叉引用检测
for (doc : allDocs) {
    String text = minioService.readMarkdown(doc.getMinioPath());  // 按需从 MinIO 读
    detect(text);
}
```

MinIO 是本地容器，一次导入几百个文档的读取在秒级，换来内存直接省掉全部文本积累。

### 二、单文件 chunk 上限 + 流式发 ES

当前逻辑：

```java
List<DocIndex> indexDocs = new ArrayList<>();
for (chunk : allChunks) {     // 没有上限
    float[] vec = aiClient.embed(chunk);
    indexDocs.add(...)         // 全攒着
}
esService.bulkIndex(indexDocs); // 最后一批发
```

改为：

```java
int chunkCount = 0;
List<DocIndex> batch = new ArrayList<>();
for (chunk : allChunks) {
    if (chunkCount >= MAX_CHUNKS_PER_DOC) break;  // 上限 100
    if (isGarbage(chunk)) continue;                // 过滤垃圾文本
    float[] vec = aiClient.embed(chunk);
    batch.add(...);
    if (batch.size() >= 200) {                     // 满 200 就发
        esService.bulkIndex(batch);
        batch.clear();
    }
    chunkCount++;
}
if (!batch.isEmpty()) esService.bulkIndex(batch);  // 剩余发掉
```

### 三、垃圾文本过滤规则

`isGarbage()` 判据：

| 规则 | 条件 | 例如 |
|------|------|------|
| 坐标指令占比 | `M 0 0 L 286.` 模式占行数 > 50% | OFD 排版坐标 |
| 乱码符号占比 | 非汉字符号（`/ _ \\ *` 等）> 80% | OCR 碎片 |
| 有效内容过短 | 去除噪声后 < 20 个汉字 | 空壳 chunk |

### 四、预期收益

| 项目 | 改前 | 改后 |
|------|------|------|
| docTextMap 内存 | ~N × 50KB（N 文件数） | 0 |
| 单文件 chunk 内存 | 无上限 | ≤ 200 条（相当于 ≤ 100KB vector） |
| Embedding 调用 | 含垃圾 chunk | 过滤后减少 |
| 导入速度 | 受垃圾拖慢 | 有上限，可预测 |

### 五、实施顺序

1. **先加 `isGarbage` 过滤器** — 效果立竿见影，减少 Embedding 浪费
2. **再加 chunk 上限和流式 ES 写入** — 锁死单文件内存上限
3. **最后改 docTextMap 走 MinIO** — 去掉唯一的内存隐患

每步独立可部署，不影响现有导入逻辑。
