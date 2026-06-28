# 导入性能优化完整方案

## 总览

优化分四个方向，按优先级排序：

| 序号 | 方向 | 问题 | 目标 |
|------|------|------|------|
| 一 | 后端异步导入 | HTTP 线程同步阻塞，前端卡死无进度 | 请求立即返回，后台运行，实时进度 |
| 二 | AI 批量 Embedding | 每个 chunk 一次 HTTP 请求，100 次往返 | 一批 chunk 一次请求，减少 99% 往返 |
| 三 | ONNX Runtime 加速 | PyTorch 框架开销，推理未用尽 CPU | 推理速度 3-4x 提升 |
| 四 | 内存防爆 + 垃圾过滤 | OFD 坐标垃圾混入，文本积累 OOM 风险 | 锁定内存上限，过滤无用文本 |

---

## 一、后端异步导入

### 问题

```
浏览器 → POST /api/import/from-dir
         → @Transactional 整个导入
         → 解压/扫描/解析/Embedding/ES/交叉引用
         → 全部完成才 return
         → HTTP 200
浏览器                                     ← 等待数十分钟，按钮转圈，超时断开
```

### 方案

**新增组件：**

1. `AsyncConfig.java` — 定义导入专用线程池
   ```java
   @Bean("importExecutor")
   public Executor importExecutor() {
       ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
       executor.setCorePoolSize(1);   // 单线程，避免并发导入争抢资源
       executor.setQueueCapacity(10);
   }
   ```

2. `submitAsyncImport()` — 异步编排
   ```java
   // ① 立即写 pending 任务进 DB
   ImportTask task = ImportTask.builder().status("pending").build();
   taskRepo.save(task);        // 立即提交，进度端点可见

   // ② 扔给线程池
   executor.submit(() -> {
       doImport(...);          // 后台慢慢跑
   });

   // ③ 立即返回 batchId（< 100ms）
   return batchId;
   ```

3. 去掉外层 `@Transactional` — 每次 `save()` 自动提交，进度轮询实时可见

### 流程对比

```
旧:
  POST → [同步跑完所有文件] → 200 OK → 前端看到结果
         ↑ 浏览器一直等，最后超时断开

新:
  POST → save pending → return batchId → 200 OK → 前端显示进度条
                       → 后台线程开始 → 每处理一个文件 update 进度 → 轮询可见
```

### 改动文件

- `AsyncConfig.java`（新增）
- `ImportService.java`（重构三入口方法 + 新增编排方法）

### 状态

代码已写，待提交部署。

---

## 二、AI 批量 Embedding 接口

### 问题

当前后端的 `processDocument` 里，每个分块调一次 AI：

```
// ImportService.java  processDocument()
for (String chunk : chunks) {
    float[] vector = aiClient.embed(chunk);  // POST /embed  × N 次
    indexDocs.add(...);
}
```

```
// AIClient.java
public float[] embed(String text) {
    EmbedRequest req = new EmbedRequest(text);
    return restClient.post().body(json).retrieve().body(EmbedResponse.class);
}
```

```
// ai-service  main.py  /embed
async def embed(req: EmbedRequest):
    embedding = embedder.encode(req.text, normalize_embeddings=True)  // 每次编码一个文本
    return embedding.tolist()
```

一个文件 100 个 chunk = 100 次 HTTP 往返。每次往返 TCP 握手开销 + uvicorn 调度 + Python 函数调用开销，实际推理只占了耗时的一小半。

### 方案

**AI 服务端** 新增 `/embed-batch` 端点：

```python
class EmbedBatchRequest(BaseModel):
    texts: List[str]        # 一次传 1~200 个文本

class EmbedBatchResponse(BaseModel):
    embeddings: List[List[float]]

@app.post("/embed-batch")
async def embed_batch(req: EmbedBatchRequest):
    embeddings = embedder.encode(
        req.texts,
        normalize_embeddings=True,
        batch_size=32,           # 内部 batch 大小
        show_progress_bar=False
    )
    return EmbedBatchResponse(embeddings=embeddings.tolist())
```

**SentenceTransformer.encode() 的内部机制：**

当传入 `List[str]` 时，模型内部会：
1. 把所有文本拼成一个大的 token 矩阵
2. 一次性做矩阵乘法（充分利用 CPU SIMD 和缓存）
3. 避免了 N 次 Python ↔ C 边界穿越

**后端 Java 端** 新增 `aiClient.embedBatch()`：

```java
public List<float[]> embedBatch(List<String> texts) {
    EmbedBatchRequest req = new EmbedBatchRequest(texts);
    EmbedBatchResponse resp = restClient.post("/embed-batch", req);
    return resp.getEmbeddings();
}
```

**后端导入流程** 改为每批 chunk 调一次 batch API：

```java
// 改前：逐 chunk 调用
for (String chunk : chunks) {
    float[] vec = aiClient.embed(chunk);  // N 次 HTTP
}

// 改后：批量调用
List<float[]> vectors = aiClient.embedBatch(chunks);  // 1 次 HTTP
```

### 效果估算

| | 逐次调用 | 批量 100 个 |
|---|---|---|
| HTTP 往返次数 | 100 次 | 1 次 |
| 单 chunk 总耗时 | ~150ms | ~20ms |
| 100 chunk 文件 | 15 秒 | 2 秒 |

### 改动文件

- `ai-service/main.py`（新增 `/embed-batch` 端点）
- `backend/.../AIClient.java`（新增 `embedBatch()` 方法）
- `backend/.../ImportService.java`（`processDocument` 改为批量调用）

---

## 三、ONNX Runtime 加速

### 原理

**当前：** PyTorch 模型推理

```
文本 → tokenize → PyTorch forward() → normalize → output
                    ↑ 包含训练框架开销
                    动态图计算图
                    未融合的算子
```

**ONNX：** 转换为优化的推理图

```
文本 → tokenize → ONNX Runtime inference → normalize → output
                    ↑ 算子融合
                    AVX-512/VNNI 指令
                    内存池复用
```

主要加速来源：
- **算子融合**：多个小操作合并（如 MatMul + Add + LayerNorm → 一个融合算子）
- **SIMD 指令**：利用 AVX-512 一次处理 16 个 float（SSE 只能 4 个）
- **VNNI 指令**：专为 int8 矩阵乘设计（如果做量化）
- **内存复用**：预分配和重用临时缓冲区，减少 `malloc/free`

### 实施

```python
# 当前 main.py
from sentence_transformers import SentenceTransformer
embedder = SentenceTransformer("BAAI/bge-large-zh-v1.5")

# 改为 ONNX 后端
from sentence_transformers import SentenceTransformer
from optimum.onnxruntime import ORTOptimizer

embedder = SentenceTransformer(
    "BAAI/bge-large-zh-v1.5",
    backend="onnx",
    model_kwargs={"provider": "CPUExecutionProvider"}
)
```

首次启动会自动下载 ONNX 格式模型，之后走优化推理。

也可以手动导出模型，加 `optimum-cli` 做更激进的优化（INT8 量化等）。

```bash
pip install "optimum[onnxruntime]"
```

### 效果估算（基于你服务器硬件）

| 条件 | 加速比 |
|------|--------|
| AVX2 | 2x |
| AVX-512 + VNNI | 3-4x |
| 加 INT8 量化 | 4-6x（精度微降） |

### 代价

- ONNX 模型文件约 400MB（按现有磁盘空间无压力）
- 首次启动需要几分钟下载/转换模型
- FP32 精度无损耗，INT8 量化轻微损失

---

## 四、多 Worker 部署

### 问题

当前 uvicorn 单进程运行：

```bash
uvicorn main:app --host 0.0.0.0 --port 8000
```

一个请求在处理时，后续请求排队等候。结合批量 Embedding 后，单请求持续时间变长（一次处理 200 个 text），排队影响更大。

### 方案

```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4
```

### 工作原理

uvicorn 用 `--workers N` 会 fork 出 N 个子进程，每个子进程加载一份模型。主进程把请求分发给空闲的子进程。

```
         ┌─ worker 1 (model copy 1) ─ 处理请求 A
请求队列 ─┼─ worker 2 (model copy 2) ─ 处理请求 B
         ├─ worker 3 (model copy 3) ─ 空闲等待
         └─ worker 4 (model copy 4) ─ 空闲等待
```

### 注意事项

| 要点 | 说明 |
|------|------|
| Worker 数量 | 建议 `CPU 核数 / 4`，你 32 线程的建议 **4-8 个** |
| 内存 | 每个 worker 加载一份模型（~1.3GB），4 worker ≈ 5.2GB |
| 模型加载 | 目前是 `lifespan` 里加载，uvicorn 多 worker 下每个 worker 独立走一遍 lifespan |
| 适用场景 | 并发导入、同时有 Embedding 和问答请求混跑 |

### 效果

与批量 Embedding 搭配：

| 场景 | 单 worker | 4 worker |
|------|-----------|----------|
| 单个导入任务（串行） | 无区别 | 无区别 |
| 多个导入任务并行 | 排队等 | 各自分到空闲 worker |
| 导入 + 问答同时 | 问答被 Embedding 阻塞 | 问答分到其他 worker 不受影响 |

---

## 五、内存防爆 + 垃圾过滤

### 问题

1. **docTextMap 积累所有文档文本** — 交叉引用时才用，N 个文件文本始终在内存
2. **单文件 chunk 无上限** — OFD 坐标垃圾可产生 1000+ chunk
3. **坐标指令混入 Embedding** — `M 0 0 L 286.004` 排版指令当作文

### 方案

#### 4.1 docTextMap → 按需读 MinIO

```java
// 改前
Map<String, String> docTextMap = new LinkedHashMap<>();
for (doc : docs) {
    String text = processDocument(doc);
    docTextMap.put(fileId, text);       // 攒着
}
for (doc : docs) {
    detect(docTextMap.get(fileId));     // 用了
}

// 改后
for (doc : docs) {
    String text = processDocument(doc);
    // 不攒了
}
for (doc : docs) {
    String text = minioService.readMarkdown(doc.getMinioPath());  // 读 MinIO
    detect(text);
}
```

MinIO 本地容器，读 500 个 markdown 文件 < 2 秒。

#### 4.2 单文件 chunk 上限 + 流式 ES

```java
final int MAX_CHUNKS_PER_DOC = 100;
List<DocIndex> batch = new ArrayList<>();

for (int i = 0; i < chunks.size(); i++) {
    if (i >= MAX_CHUNKS_PER_DOC) break;        // 上限
    if (isGarbage(chunks.get(i))) continue;     // 过滤

    float[] vec = aiClient.embed(chunks.get(i));
    batch.add(new DocIndex(...));

    if (batch.size() >= 200) {                  // 满 200 条发 ES
        esService.bulkIndex(batch);
        batch.clear();
    }
}
if (!batch.isEmpty()) esService.bulkIndex(batch);
```

#### 4.3 垃圾文本过滤

`isGarbage(text)` 判据：

| 规则 | 阈值 | 目标 |
|------|------|------|
| SVG/路径指令占比 | 匹配 `[MLQC]\s+[\d.]+[\s,]+[\d.]+` 的行 > 50% | OFD 排版坐标 |
| 非中文字符占比 | 非 CJK 字符 > 80% | OCR 碎片/乱码 |
| 有效汉字过少 | 中文汉字 < 20 个 | 空壳 chunk |

---

## 六、总体效果预估

以 468 文件、平均每文件 50 chunk 为例：

| 阶段 | 当前 | 优化后 |
|------|------|--------|
| HTTP 往返 | 23,400 次（逐 chunk） | 468 次（每文件一批） |
| Embedding 单次 RT | ~150ms | ~30ms（batch + ONNX） |
| 垃圾 chunk | 100% 通过 | 过滤 30-60% |
| 总导入时间 | 数小时 | **20-40 分钟** |
| OOM 风险 | 有 | 锁定 |
| 进度可见 | 无（事务未提交） | 实时轮询 |

## 七、实施顺序

```
第一步：后端异步导入 ──── 代码已完成，待部署
     ↓
第二步：AI 批量 Embedding ──── 改 main.py + AIClient.java + processDocument
     ↓
第三步：垃圾文本过滤 ──── 加 isGarbage()，收效最快
     ↓
第四步：chunk 上限 + 流式 ES ──── 防内存爆炸
     ↓
第五步：ONNX Runtime ──── 进一步压榨 CPU
     ↓
第六步：多 Worker 部署 ──── uvicorn --workers 4，提升并发能力
     ↓
第七步：docTextMap → MinIO ──── 清除最后的内存风险
```

每步独立可部署，互不依赖。
