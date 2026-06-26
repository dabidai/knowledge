#!/bin/bash
# ============================================================
# 知识库项目 — 正式部署脚本
# 用法: bash deploy.sh [frontend|backend|all]
# ============================================================
set -euo pipefail

BASE_DIR="$HOME/llm/docker/anythingllm/knowledge"
NGINX_CONF_DIR="/etc/nginx/sites-available"
DOMAIN="${DOMAIN:-localhost}"

# 颜色
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ──── 后端部署 ────
deploy_backend() {
  info "===== 编译后端 ====="
  cd "$BASE_DIR/backend"
  mvn package -DskipTests -q
  info "后端编译完成"

  # 停止旧进程
  local old_pid
  old_pid=$(ss -tlnp | grep 8080 | grep -oP 'pid=\K\d+') || true
  if [ -n "$old_pid" ]; then
    info "停止旧后端进程 (PID: $old_pid)"
    kill "$old_pid" 2>/dev/null || true
    sleep 1
  fi

  # 启动
  info "启动后端服务 (端口 8080)"
  nohup java -jar "$BASE_DIR/backend/target/knowledge-base-0.1.0.jar" \
    > "$BASE_DIR/backend/app.log" 2>&1 &

  # 等待就绪
  for i in $(seq 1 10); do
    if ss -tlnp | grep -q 8080; then
      info "后端启动成功 (PID: $(ss -tlnp | grep 8080 | grep -oP 'pid=\K\d+'))"
      return 0
    fi
    sleep 1
  done

  error "后端启动超时，请查看日志: tail -50 $BASE_DIR/backend/app.log"
  return 1
}

# ──── 前端部署 ────
deploy_frontend() {
  info "===== 构建前端 ====="
  cd "$BASE_DIR/frontend"

  npm install --silent
  npm run build
  info "前端构建完成 → dist/"

  # 如有旧 nginx 配置，启用
  if [ -f "$NGINX_CONF_DIR/knowledge" ]; then
    sudo ln -sf "$NGINX_CONF_DIR/knowledge" /etc/nginx/sites-enabled/ 2>/dev/null || true
    sudo nginx -t && sudo systemctl reload nginx
    info "Nginx 配置已重载"
  else
    info "Nginx 配置文件不存在，跳过重载"
    info "请先执行: sudo bash $BASE_DIR/deploy.sh nginx"
  fi
}

# ──── Nginx 配置 ────
deploy_nginx() {
  info "===== 配置 Nginx ====="

  # 安装 Nginx
  if ! command -v nginx &>/dev/null; then
    info "安装 Nginx..."
    sudo apt-get update -qq && sudo apt-get install -y -qq nginx
  fi

  # 写入配置
  sudo tee "$NGINX_CONF_DIR/knowledge" > /dev/null << 'NGINX_EOF'
server {
    listen 80;
    server_name _;

    # 前端静态文件
    root /home/ubantu/llm/docker/anythingllm/knowledge/frontend/dist;
    index index.html;

    # Gzip 压缩
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml text/javascript image/svg+xml;
    gzip_min_length 1024;

    # API 反向代理到后端
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
        # 大文件上传
        client_max_body_size 4096M;
    }

    # SPA 路由
    location / {
        try_files $uri $uri/ /index.html;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
NGINX_EOF

  # 启用站点
  sudo ln -sf "$NGINX_CONF_DIR/knowledge" /etc/nginx/sites-enabled/
  sudo rm -f /etc/nginx/sites-enabled/default

  # 测试并重载
  if sudo nginx -t; then
    sudo systemctl enable nginx 2>/dev/null || true
    sudo systemctl reload nginx || sudo systemctl start nginx
    info "Nginx 配置成功，已启动"
  else
    error "Nginx 配置测试失败，请检查: sudo nginx -t"
    return 1
  fi
}

# ──── 入口 ────
case "${1:-all}" in
  backend)
    deploy_backend
    ;;
  frontend)
    deploy_frontend
    ;;
  nginx)
    deploy_nginx
    ;;
  all)
    deploy_backend
    deploy_nginx
    deploy_frontend
    info "===== 部署完成 ====="
    info "访问 http://$(curl -s ifconfig.me 2>/dev/null || echo '服务器IP')"
    ;;
  *)
    echo "用法: bash deploy.sh [frontend|backend|nginx|all]"
    exit 1
    ;;
esac
