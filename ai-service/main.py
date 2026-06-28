"""
知识库 AI 服务 —— FastAPI
提供 Embedding 生成 + RAG 问答（通过 Ollama 调用本地 LLM）
"""
import os
import logging
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from langchain_ollama import ChatOllama
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ============ 配置 ============
OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:8b-q4_K_M")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-large-zh-v1.5")
EMBEDDING_BACKEND = os.getenv("EMBEDDING_BACKEND", "pytorch")  # "pytorch" | "onnx"

# ============ 全局模型实例 ============
embedder: Optional[SentenceTransformer] = None
llm: Optional[ChatOllama] = None

# ============ RAG Prompt 模板 ============
RAG_SYSTEM = """你是一个政府公文知识库智能助手。请根据提供的文档片段和结构化数据回答用户的问题。

规则：
1. 仅根据提供的资料回答，不要编造信息
2. 上下文可能包含文档正文片段和「相关事项及签阅记录」
3. 签阅记录中包含签阅人、签阅时间、签阅意见
4. 如果问题涉及签约人、签阅意见等结构化信息，从签阅记录中查找
5. 如果资料不足以回答问题，请明确说明"根据已有资料无法确定"
6. 回答要简洁专业，使用正式语言
7. 如果涉及多个文档，请分别引用
8. 在答案末尾列出引用的文档名称或事项文号"""

RAG_PROMPT = ChatPromptTemplate.from_messages([
    ("system", RAG_SYSTEM),
    ("user", """文档片段：
{context}

---

用户问题：{question}

请回答："""),
])

# 多轮对话 Prompt 模板
CHAT_SYSTEM = """你是一个政府公文知识库智能助手。你的职责是：
1. 根据提供的文档片段和结构化事项数据回答用户问题
2. 上下文中可能包含「相关事项及签阅记录」，其中列出了事项的元数据（标题、分类、文号、发文单位、年度、类型）和签阅人信息
3. 如果用户询问签约人、签阅意见、事项属性等信息，优先从结构化事项数据中查找
4. 如果文档中有明确信息，直接回答并引用来源
5. 如果信息不足，明确说明"根据已有资料无法确定"，不要编造
6. 保持正式、专业的公文风格
7. 如果用户问题与知识库无关，也可以正常聊天回答
8. 回答末尾标注信息来源（文档编号或事项名称）"""


# ============ 构建聊天消息 ============

def build_chat_messages(contexts: List[str], history: Optional[List[dict]], question: str):
    """构建带历史的多轮对话消息列表"""
    messages = [("system", CHAT_SYSTEM)]

    # 注入历史对话
    if history:
        for msg in history[-10:]:  # 最多保留 10 轮
            role = msg.get("role", "user")
            content = msg.get("content", "")
            if role in ("user", "assistant"):
                messages.append((role, content))

    # 注入文档上下文 + 当前问题
    if contexts:
        parts = []
        for i, ctx in enumerate(contexts[:12]):
            if ctx.startswith("\n【相关事项"):
                # 结构化事项数据，不加文档前缀
                parts.append(ctx.strip())
            else:
                parts.append(f"[文档{i+1}] {ctx[:1500]}")
        ctx_text = "\n\n---\n\n".join(parts)
        messages.append(("user", f"""根据以下资料回答我的问题。

资料：
{ctx_text}

---

我的问题：{question}

请回答（并引用来源）："""))
    else:
        messages.append(("user", question))

    return messages


@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时加载模型，关闭时释放资源"""
    global embedder, llm

    # Embedding 模型 —— 支持 ONNX 后端加速
    logger.info(f"加载 Embedding 模型: {EMBEDDING_MODEL}, 后端: {EMBEDDING_BACKEND}")
    if EMBEDDING_BACKEND == "onnx":
        try:
            embedder = SentenceTransformer(
                EMBEDDING_MODEL,
                backend="onnx",
                model_kwargs={"provider": "CPUExecutionProvider"}
            )
            logger.info("Embedding 模型加载完成 (ONNX 后端)")
        except Exception as e:
            logger.warning(f"ONNX 后端加载失败，回退至 PyTorch: {e}")
            embedder = SentenceTransformer(EMBEDDING_MODEL)
            logger.info("Embedding 模型加载完成 (PyTorch 回退)")
    else:
        embedder = SentenceTransformer(EMBEDDING_MODEL)
        logger.info("Embedding 模型加载完成 (PyTorch 后端)")

    logger.info(f"连接 Ollama: {OLLAMA_HOST}, 模型: {OLLAMA_MODEL}")
    llm = ChatOllama(
        base_url=OLLAMA_HOST,
        model=OLLAMA_MODEL,
        temperature=0.1,
        num_ctx=4096,
    )
    logger.info("Ollama 连接成功")

    yield

    logger.info("AI 服务关闭")


app = FastAPI(title="知识库 AI 服务", version="0.1.0", lifespan=lifespan)


# ============ 请求/响应模型 ============

class EmbedRequest(BaseModel):
    text: str

class EmbedResponse(BaseModel):
    embedding: List[float]

class EmbedBatchRequest(BaseModel):
    texts: List[str]

class EmbedBatchResponse(BaseModel):
    embeddings: List[List[float]]

class AskRequest(BaseModel):
    question: str
    contexts: List[str]
    history: Optional[List[dict]] = None  # {role: "user"|"assistant", content: str}

class AskResponse(BaseModel):
    answer: str


class ChatRequest(BaseModel):
    question: str
    contexts: List[str]
    history: Optional[List[dict]] = None  # 多轮对话历史


# ============ API 端点 ============

@app.get("/health")
async def health():
    return {"status": "UP", "service": "ai-service"}


@app.post("/embed", response_model=EmbedResponse)
async def embed(req: EmbedRequest):
    """将文本转换为向量"""
    if embedder is None:
        raise HTTPException(status_code=503, detail="Embedding 模型未加载")

    try:
        embedding = embedder.encode(req.text, normalize_embeddings=True)
        return EmbedResponse(embedding=embedding.tolist())
    except Exception as e:
        logger.error(f"Embedding 失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/embed-batch", response_model=EmbedBatchResponse)
async def embed_batch(req: EmbedBatchRequest):
    """批量将多个文本转换为向量 —— 一次 HTTP 请求处理多条，减少网络往返"""
    if embedder is None:
        raise HTTPException(status_code=503, detail="Embedding 模型未加载")

    try:
        embeddings = embedder.encode(
            req.texts,
            normalize_embeddings=True,
            batch_size=32,
            show_progress_bar=False
        )
        return EmbedBatchResponse(embeddings=embeddings.tolist())
    except Exception as e:
        logger.error(f"批量 Embedding 失败: texts={len(req.texts)}, error={e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/ask", response_model=AskResponse)
async def ask(req: AskRequest):
    """RAG 问答 —— 基于文档上下文回答问题（兼容旧接口）"""
    if llm is None:
        raise HTTPException(status_code=503, detail="LLM 未连接")

    try:
        contexts_text = "\n\n---\n\n".join(
            f"[文档{i+1}] {ctx[:1500]}" for i, ctx in enumerate(req.contexts[:10])
        )

        chain = RAG_PROMPT | llm | StrOutputParser()
        answer = chain.invoke({
            "context": contexts_text,
            "question": req.question,
        })

        logger.info(f"问答完成, 问题长度={len(req.question)}, 答案长度={len(answer)}")
        return AskResponse(answer=answer)

    except Exception as e:
        logger.error(f"问答失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/chat", response_model=AskResponse)
async def chat(req: ChatRequest):
    """智能体对话 —— 支持多轮历史 + RAG 知识库上下文"""
    if llm is None:
        raise HTTPException(status_code=503, detail="LLM 未连接")

    try:
        messages = build_chat_messages(req.contexts, req.history, req.question)
        prompt = ChatPromptTemplate.from_messages(messages)
        chain = prompt | llm | StrOutputParser()

        answer = chain.invoke({})

        logger.info(f"对话完成, 历史轮次={len(req.history) if req.history else 0}, "
                    f"上下文数={len(req.contexts)}, 答案长度={len(answer)}")
        return AskResponse(answer=answer)

    except Exception as e:
        logger.error(f"对话失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
