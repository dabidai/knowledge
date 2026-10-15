FROM python:3.12-slim

WORKDIR /app
ARG EMBEDDING_BACKEND=pytorch
ENV EMBEDDING_BACKEND=${EMBEDDING_BACKEND}

# 使用清华镜像源加速（国内网络）
RUN pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple

# 安装 PyTorch CPU 版
RUN pip install --no-cache-dir torch==2.5.1 --index-url https://download.pytorch.org/whl/cpu

COPY requirements*.txt ./
RUN if [ "$EMBEDDING_BACKEND" = "onnx" ]; then \
      pip install --no-cache-dir -r requirements-onnx.txt; \
    elif [ "$EMBEDDING_BACKEND" = "pytorch" ]; then \
      pip install --no-cache-dir -r requirements.txt; \
    else echo "Invalid EMBEDDING_BACKEND" >&2; exit 1; fi

COPY main.py settings.py ./

# 预先下载嵌入模型，避免启动时联网
ENV HF_ENDPOINT=https://hf-mirror.com
ENV HF_HOME=/app/.cache/huggingface
RUN python -c "import os, shutil; from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-large-zh-v1.5', backend=os.environ['EMBEDDING_BACKEND']).save('/opt/models/bge-large-zh-v1.5'); shutil.rmtree('/app/.cache/huggingface')"
ENV EMBEDDING_MODEL=/opt/models/bge-large-zh-v1.5
ENV HF_HUB_OFFLINE=1
RUN useradd --uid 10001 --create-home appuser && mkdir -p /app/.cache/huggingface && chown -R appuser:appuser /app
USER appuser

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health/live', timeout=3)"

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
