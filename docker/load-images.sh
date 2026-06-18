#!/bin/bash
# ============================================
# Linux 端：加载镜像 → 启动服务
# ============================================

set -e
cd "$(dirname "$0")"

# 1. 加载镜像
echo "[1/3] 加载镜像..."
docker load -i kb-images.tar

# 2. 拉取公共镜像（如果服务器能连 Docker Hub）
echo "[2/3] 拉取公共镜像..."
docker pull nginx:1.27-alpine       || echo "nginx 拉取失败，如已加载本地镜像可忽略"
docker pull postgres:16-alpine      || echo "postgres 拉取失败"
docker pull redis:7-alpine          || echo "redis 拉取失败"
docker pull minio/minio:RELEASE.2024-11-07T00-52-20Z || echo "minio 拉取失败"
docker pull neo4j:5-community       || echo "neo4j 拉取失败"

# 3. 启动（不用 --build，直接用已加载的镜像）
echo "[3/3] 启动服务..."
docker compose up -d

echo "========================================"
echo "启动完成，查看状态: docker compose ps"
echo "查看日志: docker compose logs -f"
echo "========================================"
