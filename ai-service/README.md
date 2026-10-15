# AI 服务运行与验证

默认模型为 `BAAI/bge-large-zh-v1.5`（1024 维）及 `qwen3:32b`，默认后端为 PyTorch。不会自动从 ONNX 回退到 PyTorch，加载失败时启动明确报错。

## 本机运行

在 Python 3.12 虚拟环境安装 `pip install -r requirements.txt`，然后在本目录运行 `uvicorn main:app --host 127.0.0.1 --port 8000`。切换 ONNX 时安装 `requirements-onnx.txt`，并设置 `EMBEDDING_BACKEND=onnx`；本地模型目录通过 `EMBEDDING_MODEL` 指定。根目录 `start.sh` 会优先保留已有的 `models/bge-large-zh-v1.5-local`，也允许环境变量覆盖。

## Docker

默认使用 `docker compose -f docker/docker-compose.yml build ai-service` 构建 PyTorch 镜像。需要 ONNX 时，在构建和启动这两个步骤都设置同一个 `EMBEDDING_BACKEND=onnx` 环境变量。Compose 已将该变量同时传递为构建参数和运行配置。

模型在构建阶段保存到镜像 `/opt/models/bge-large-zh-v1.5`；运行时离线读取，不再用宿主缓存挂载覆盖构建产物。此修改需要重建镜像，旧宿主模型缓存未删除。不要只向 PyTorch 旧镜像传递 ONNX 环境变量。

裸机只监听回环地址；容器通过内部网络提供 AI 接口，不发布宿主端口；Nginx `/ai/` 返回 404。业务请求统一通过 Java `/api/` 入口。

## 资源与输入限制

| 配置 | 默认值 |
|---|---|
| `EMBEDDING_CONCURRENCY` | 1 |
| `GENERATION_CONCURRENCY` | 2 |
| `GENERATION_TIMEOUT_SECONDS` | 180 秒 |
| `OLLAMA_NUM_CTX` / `OLLAMA_NUM_PREDICT` | 4096 / 768 |

忙时立即返回 503 和 Retry-After，不无限排队。请求体上限 1 MiB，问题最多 1000 字符，单条向量输入最多 2048 字符；每批最多 100 条、总共 60000 字符。历史最多 20 条、总共 64000 字符；上下文最多 20 条、总共 200000 字符。超限返回 413/422。

进入 LLM 的资料和最近 10 条历史还会按 UTF-8 字节作保守预算，预留输出和消息模板空间，包含结构化签阅资料；裁剪通过 `input_truncated` 返回并在 Java 日志记录。这不是精确 token 计数，长问题即使未达到 1000 字符也可能超过当前模型预算而被拒绝。若要扩大窗口，需按实际模型和硬件验证后调整配置。

Java 分别使用单条向量 30 秒、批量向量 180 秒、生成 190 秒、健康检查 5 秒的读取超时。关键词检索降级通过 `degraded`/`warning` 返回并显示；LLM 故障作为 API 错误处理，不保存 assistant 故障文本。失败前已提交的用户消息保留。

## 探测与测试

- `/health` 和 `/health/live`：进程存活，不调用模型。
- `/health/ready`：模型加载状态及短超时 Ollama `/api/tags` 检查，确认配置的生成模型已安装；失败返回 503，不执行生成。Java 运维检查使用此接口。Ollama 故障不影响仍可用的向量接口。
- Docker 健康探针检查 live，避免仅因 Ollama 不可用将向量服务整体判死。

不下载模型的接口回归：在独立虚拟环境中 `pip install -r requirements-test.txt`，然后在本目录执行 `python -m unittest discover -s tests -v`。测试使用替身覆盖真实 LangChain 消息、输入限制、向量校验、并发/取消、模型故障和健康探测，不连接业务数据库。

后端相关测试：在 `backend` 运行 `mvn -Dtest=AIClientTest,AIFailureControllerTest,PermissionEndpointsTest,ElasticsearchPermissionTest,ConversationPermissionTest test`，均为单元测试，不启动真实数据库。
