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
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:14b-q4_K_M")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-large-zh-v1.5")

# ============ 全局模型实例 ============
embedder: Optional[SentenceTransformer] = None
llm: Optional[ChatOllama] = None

# ============ RAG Prompt 模板 ============
RAG_PROMPT = ChatPromptTemplate.from_messages([
    ("system", """你是一个政府公文知识库助手。请根据以下文档片段回答用户的问题。

规则：
1. 仅根据提供的文档片段回答，不要编造信息
2. 如果文档片段不足以回答问题，请明确说明"根据已有资料无法确定"
3. 回答要简洁专业，使用正式语言
4. 如果涉及多个文档，请分别引用
5. 在答案末尾列出引用的文档名称

文档片段：
{context}

---

用户问题：{question}

请回答："""),
])


@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时加载模型，关闭时释放资源"""
    global embedder, llm

    logger.info(f"加载 Embedding 模型: {EMBEDDING_MODEL}")
    embedder = SentenceTransformer(EMBEDDING_MODEL)
    logger.info("Embedding 模型加载完成")

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

class AskRequest(BaseModel):
    question: str
    contexts: List[str]

class AskResponse(BaseModel):
    answer: str


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


@app.post("/ask", response_model=AskResponse)
async def ask(req: AskRequest):
    """RAG 问答 —— 基于文档上下文回答问题"""
    if llm is None:
        raise HTTPException(status_code=503, detail="LLM 未连接")

    try:
        # 组装上下文（限制总长度避免超出上下文窗口）
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


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
