FROM python:3.12-slim

WORKDIR /app

# 安装 PyTorch CPU 版（减小镜像体积）
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

EXPOSE 8000

# 首次启动时会自动下载 bge-large-zh-v1.5 (~1.3GB)
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
