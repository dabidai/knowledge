#!/bin/bash
# ============================================================
# 知识库项目 — 一键启动脚本（AI服务 + 后端 + 前端）
# 用法: bash start.sh
# ============================================================
set -euo pipefail

BASE_DIR="$HOME/llm/docker/anythingllm/knowledge"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
ok()    { echo -e "  ${GREEN}✔${NC} $1"; }
fail()  { echo -e "  ${RED}✘${NC} $1"; }

# 等待指定端口出现（最多等 N 秒）
wait_for_port() {
  local port=$1 label=$2 timeout=${3:-30}
  info "等待 $label (端口 $port) 就绪..."
  for i in $(seq 1 "$timeout"); do
    if ss -tlnp | grep -qE ":$port "; then
      ok "$label 已就绪 (${i}s)"
      return 0
    fi
    sleep 1
  done
  fail "$label 超时 (${timeout}s)，请检查日志"
  return 1
}

# ──── 1. AI 服务（FastAPI，端口 8000） ────
start_ai() {
  echo ""
  echo -e "${CYAN}══════════════════════════════════════════════════${NC}"
  echo -e "${CYAN}  1/3  启动 AI 服务 (FastAPI :8000)${NC}"
  echo -e "${CYAN}══════════════════════════════════════════════════${NC}"

  cd "$BASE_DIR/ai-service"

  # 激活虚拟环境
  if [ ! -d venv ]; then
    error "虚拟环境不存在，请先执行: python3 -m venv venv && venv/bin/pip install -r requirements.txt"
    return 1
  fi
  source venv/bin/activate

  # 停旧进程
  local old_pid
  old_pid=$(ss -tlnp | grep 8000 | grep -oP 'pid=\K\d+') || true
  if [ -n "$old_pid" ]; then
    info "停止旧 AI 进程 (PID: $old_pid)"
    kill "$old_pid" 2>/dev/null || true
    sleep 2
  fi

  # 启动
  EMBEDDING_MODEL=./models/bge-large-zh-v1.5-local \
  OLLAMA_MODEL=qwen3:32b \
  nohup uvicorn main:app --host 0.0.0.0 --port 8000 > ai-service.log 2>&1 &

  wait_for_port 8000 "AI 服务" 15
}

# ──── 2. 后端（Spring Boot，端口 8080） ────
start_backend() {
  echo ""
  echo -e "${CYAN}══════════════════════════════════════════════════${NC}"
  echo -e "${CYAN}  2/3  启动后端 (Spring Boot :8080)${NC}"
  echo -e "${CYAN}══════════════════════════════════════════════════${NC}"

  cd "$BASE_DIR/backend"

  # 编译
  info "编译后端..."
  mvn package -DskipTests -q
  ok "编译完成"

  # 停旧进程
  local old_pid
  old_pid=$(ss -tlnp | grep 8080 | grep -oP 'pid=\K\d+') || true
  if [ -n "$old_pid" ]; then
    info "停止旧后端进程 (PID: $old_pid)"
    kill "$old_pid" 2>/dev/null || true
    sleep 2
  fi

  # 启动，日志按小时轮转
  LOG_FILE="app-$(date +%Y%m%d-%H).log"
  nohup java -jar target/knowledge-base-0.1.0.jar > "$LOG_FILE" 2>&1 &
  ln -sf "$LOG_FILE" app.log

  wait_for_port 8080 "后端" 30
}

# ──── 3. 前端（Vue，端口 3000） ────
start_frontend() {
  echo ""
  echo -e "${CYAN}══════════════════════════════════════════════════${NC}"
  echo -e "${CYAN}  3/3  启动前端 (Vue :3000)${NC}"
  echo -e "${CYAN}══════════════════════════════════════════════════${NC}"

  cd "$BASE_DIR/frontend"

  # 停旧进程
  local old_pid
  old_pid=$(ss -tlnp | grep 3000 | grep -oP 'pid=\K\d+') || true
  if [ -n "$old_pid" ]; then
    info "停止旧前端进程 (PID: $old_pid)"
    kill "$old_pid" 2>/dev/null || true
    sleep 1
  fi

  # 启动
  nohup npm run dev -- --host > frontend.log 2>&1 &

  wait_for_port 3000 "前端" 15
}

# ──── 状态汇总 ────
show_status() {
  echo ""
  echo -e "${CYAN}══════════════════════════════════════════════════${NC}"
  echo -e "${CYAN}  启动状态${NC}"
  echo -e "${CYAN}══════════════════════════════════════════════════${NC}"

  local all_ok=true

  local host="http://10.229.48.105"

  if ss -tlnp | grep -qE ':8000 '; then
    ok "AI 服务    $host:8000"
  else
    fail "AI 服务    未启动"
    all_ok=false
  fi

  if ss -tlnp | grep -qE ':8080 '; then
    ok "后端       $host:8080"
  else
    fail "后端       未启动"
    all_ok=false
  fi

  if ss -tlnp | grep -qE ':3000 '; then
    ok "前端       $host:3000"
  else
    fail "前端       未启动"
    all_ok=false
  fi

  echo ""
  if $all_ok; then
    info "所有服务已启动！"
    echo "  前端: $host:3000"
    echo "  后端: $host:8080/api/health"
    echo "  AI:   $host:8000/docs"
  else
    warn "部分服务启动失败，请查看日志:"
    echo "  AI 服务日志:    tail -50 $BASE_DIR/ai-service/ai-service.log"
    echo "  后端日志:       tail -50 $BASE_DIR/backend/app.log"
    echo "  前端日志:       tail -50 $BASE_DIR/frontend/frontend.log"
  fi
}

# ──── 入口 ────
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║     知识库项目 — 一键启动                       ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════╝${NC}"

start_ai
start_backend
start_frontend
show_status
