"""单机 AI 服务配置；无效配置在启动时明确失败。"""
import os


def positive_int(name: str, default: int) -> int:
    value = int(os.getenv(name, str(default)))
    if value <= 0:
        raise ValueError(f"{name} 必须大于 0")
    return value


OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434").rstrip("/")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:32b")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-large-zh-v1.5")
EMBEDDING_BACKEND = os.getenv("EMBEDDING_BACKEND", "pytorch")
if EMBEDDING_BACKEND not in ("pytorch", "onnx"):
    raise ValueError("EMBEDDING_BACKEND 必须为 pytorch 或 onnx")

EMBEDDING_DIMENSIONS = positive_int("EMBEDDING_DIMENSIONS", 1024)
EMBEDDING_CONCURRENCY = positive_int("EMBEDDING_CONCURRENCY", 1)
GENERATION_CONCURRENCY = positive_int("GENERATION_CONCURRENCY", 2)
GENERATION_TIMEOUT_SECONDS = positive_int("GENERATION_TIMEOUT_SECONDS", 180)
HEALTH_TIMEOUT_SECONDS = positive_int("HEALTH_TIMEOUT_SECONDS", 3)
OLLAMA_NUM_CTX = positive_int("OLLAMA_NUM_CTX", 4096)
OLLAMA_NUM_PREDICT = positive_int("OLLAMA_NUM_PREDICT", 768)
if OLLAMA_NUM_CTX <= OLLAMA_NUM_PREDICT + 512:
    raise ValueError("OLLAMA_NUM_CTX 必须为输入和输出预留足够空间")

MAX_BODY_BYTES = 1024 * 1024
MAX_QUESTION_CHARS = 1000
MAX_EMBED_CHARS = 2048
MAX_BATCH_SIZE = 100
MAX_BATCH_CHARS = 60000
MAX_CONTEXT_CHARS = 100000
MAX_CONTEXT_TOTAL_CHARS = 200000
MAX_HISTORY_CHARS = 16000
MAX_HISTORY_TOTAL_CHARS = 64000
