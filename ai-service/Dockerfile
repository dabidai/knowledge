FROM python:3.12-slim

WORKDIR /app

# 使用清华镜像源加速（国内网络）
RUN pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple

# 安装 PyTorch CPU 版
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

# 预先下载嵌入模型，避免启动时联网
ENV HF_ENDPOINT=https://hf-mirror.com
ENV HF_HOME=/app/.cache/huggingface
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-large-zh-v1.5')"

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
