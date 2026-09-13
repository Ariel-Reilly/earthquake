#!/usr/bin/env bash
# start-dev.sh — 地脉智巡一键全栈启动（Linux / macOS / WSL）
#
# 用法：
#   ./start-dev.sh                  # 默认：仅启动前端
#   ./start-dev.sh all              # 后端 + 前端 + AI 服务
#   ./start-dev.sh backend          # 仅后端
#   ./start-dev.sh frontend         # 仅前端
#   ./start-dev.sh ai               # 仅 AI 服务
#   ./start-dev.sh infra up|down|reset
#   ./start-dev.sh status           # 健康度速览（端口 / Docker / Git）
#   ./start-dev.sh doctor           # 诊断常见问题
#
# 依赖：Docker / Node.js 18+ / JDK 17+ / Python 3.10+

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
CMD="${1:-frontend}"
SPRING_PROFILE="${SPRING_PROFILE:-default}"
JWT_SECRET="${JWT_SECRET:-geosentry-dev-secret-key-at-least-32-chars-long-xyz}"

# ─── 颜色 ───
if [ -t 1 ]; then
  C_CYAN='\033[0;36m'
  C_GREEN='\033[0;32m'
  C_YELLOW='\033[0;33m'
  C_RED='\033[0;31m'
  C_RESET='\033[0m'
else
  C_CYAN=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_RESET=""
fi

step()  { printf "\n${C_CYAN}▶ %s${C_RESET}\n" "$1"; }
ok()    { printf "  ${C_GREEN}[OK]${C_RESET} %s\n" "$1"; }
warn()  { printf "  ${C_YELLOW}[!]${C_RESET} %s\n" "$1"; }
err()   { printf "  ${C_RED}[X]${C_RESET} %s\n" "$1"; }
info()  { printf "      %s\n" "$1"; }

# ─── 端口检测 ───
port_listening() {
  local port=$1
  if command -v lsof >/dev/null 2>&1; then
    lsof -i :$port -sTCP:LISTEN >/dev/null 2>&1
  elif command -v ss >/dev/null 2>&1; then
    ss -lnt 'sport = :'$port >/dev/null 2>&1
  else
    netstat -lnt 2>/dev/null | grep -q ":$port "
  fi
}

free_port() {
  local port=$1
  if port_listening "$port"; then
    warn "Port $port 在被占用，尝试释放..."
    local pid
    pid=$(lsof -ti :$port 2>/dev/null || true)
    if [ -n "$pid" ]; then
      kill -9 "$pid" 2>/dev/null || true
      sleep 1
    fi
  fi
}

stop_docker_container() {
  local port=$1
  local name=$2
  if port_listening "$port"; then
    if command -v docker >/dev/null 2>&1; then
      if docker compose ps --services --filter "status=running" 2>/dev/null | grep -q "^${name}$"; then
        warn "Stopping Docker container $name..."
        (cd "$PROJECT_ROOT" && docker compose stop "$name" >/dev/null 2>&1)
        sleep 2
      fi
    fi
  fi
}

# ─────────────────────────────────────────────
# 基础设施
# ─────────────────────────────────────────────
infra() {
  local action="${1:-up}"
  case "$action" in
    up)
      step "启动基础设施..."
      cd "$PROJECT_ROOT" && docker compose up -d postgres redis minio emqx tdengine
      info "等待端口就绪..."
      local ports=(15432 16379 19000 1883 16041)
      for i in $(seq 1 30); do
        sleep 2
        local ready=0
        for p in "${ports[@]}"; do
          if port_listening "$p"; then ((ready++)); fi
        done
        printf "\r      就绪 %d/%d" "$ready" "${#ports[@]}"
        if [ "$ready" -eq "${#ports[@]}" ]; then
          printf "\n"
          ok "所有基础设施已就绪"
          return
        fi
      done
      printf "\n"
      warn "部分端口未就绪，请检查 docker compose ps"
      ;;
    down)
      step "停止基础设施..."
      cd "$PROJECT_ROOT" && docker compose down
      ;;
    reset)
      step "销毁并重建基础设施..."
      cd "$PROJECT_ROOT" && docker compose down -v && docker compose up -d postgres redis minio emqx tdengine
      ;;
    *)
      err "用法: $0 infra up|down|reset"
      exit 1
      ;;
  esac
}

# ─────────────────────────────────────────────
# 后端
# ─────────────────────────────────────────────
backend() {
  step "启动后端 (Spring Boot)"

  stop_docker_container 8080 "backend"
  free_port 8080

  # 检查基础设施
  local ports_ok=true
  for entry in "15432:Postgres" "16379:Redis" "19000:MinIO"; do
    p="${entry%%:*}"; n="${entry##*:}"
    if port_listening "$p"; then
      ok "$n on $p"
    else
      err "$n on $p NOT listening"
      info "启动: docker compose up -d ${n,,}"
      ports_ok=false
    fi
  done
  if [ "$ports_ok" = false ]; then
    err "基础设施未就绪。运行: $0 infra up"
    return 1
  fi

  export SPRING_PROFILES_ACTIVE="$SPRING_PROFILE"
  export POSTGRES_PORT=15432
  export REDIS_PORT=16379
  export REDIS_HOST=127.0.0.1
  export REDIS_PASSWORD=redis123
  export MINIO_ENDPOINT=http://127.0.0.1:19000
  export JWT_SECRET="$JWT_SECRET"
  export DEV_USERS_ENABLED=true
  export DEVICE_AUTH_STRICT=false
  export TDENGINE_ENABLED=false
  export EMQX_ENABLED=false
  export AI_ENABLED=false

  info "POSTGRES_PORT=15432 REDIS_PORT=16379 MINIO=19000 PROFILE=$SPRING_PROFILE"

  cd "$PROJECT_ROOT/backend"
  exec mvn spring-boot:run -DskipTests
}

# ─────────────────────────────────────────────
# 前端
# ─────────────────────────────────────────────
frontend() {
  step "启动前端 (Vite dev server)"

  stop_docker_container 5173 "frontend"
  free_port 5173

  if port_listening 8080; then
    ok "Backend reachable on 8080"
  else
    warn "Backend on 8080 NOT running. Run: $0 backend"
  fi

  export VITE_USE_BACKEND=true
  export VITE_API_BASE_URL=http://localhost:8080/api

  cd "$PROJECT_ROOT"
  if [ ! -d "node_modules" ]; then
    info "首次运行，安装 npm 依赖..."
    npm install
  fi

  exec npm run dev
}

# ─────────────────────────────────────────────
# AI 服务
# ─────────────────────────────────────────────
ai() {
  step "启动 AI 微服务 (FastAPI)"

  free_port 8001

  cd "$PROJECT_ROOT/ai-service"
  if [ ! -d ".venv" ]; then
    info "首次运行，创建虚拟环境..."
    python3 -m venv .venv
  fi
  # shellcheck disable=SC1091
  source .venv/bin/activate
  info "安装依赖..."
  pip install -q -r requirements.txt
  info "启动 Uvicorn on 8001..."
  exec uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
}

# ─────────────────────────────────────────────
# 全栈
# ─────────────────────────────────────────────
all() {
  step "一键全栈启动"
  if ! port_listening 15432; then
    infra up
  else
    ok "基础设施已在运行"
  fi

  cat <<EOF

============================================================
  全栈模式需要 3 个终端，分别执行：
    Terminal 1 (后端):    $0 backend
    Terminal 2 (AI):      $0 ai
    Terminal 3 (前端):    $0 frontend

  后续可访问：
    前端:    http://localhost:5173
    后端:    http://localhost:8080/swagger-ui.html
    AI 服务: http://localhost:8001/docs
============================================================
EOF

  frontend
}

# ─────────────────────────────────────────────
# 健康度速览（端口 / Docker / Git）
# ─────────────────────────────────────────────
status() {
  step "健康度速览"

  local entries=(
    "15432:Postgres"
    "16379:Redis"
    "19000:MinIO API"
    "19001:MinIO UI"
    "11883:EMQX MQTT"
    "28083:EMQX UI"
    "16030:TDengine TCP"
    "16041:TDengine HTTP"
    "5173:Frontend (Vite)"
    "8080:Backend (Spring Boot)"
    "8001:AI Service (FastAPI)"
  )

  for entry in "${entries[@]}"; do
    port="${entry%%:*}"
    name="${entry##*:}"
    if port_listening "$port"; then
      ok "$name port $port OK"
    else
      err "$name port $port DOWN"
    fi
  done

  echo
  info "Docker 状态："
  if command -v docker >/dev/null 2>&1; then
    if docker info >/dev/null 2>&1; then
      ok "Docker 在线"
      local geosentry
      geosentry=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -E 'geosentry' || true)
      if [ -n "$geosentry" ]; then
        info "geosentry 容器：$geosentry"
      else
        warn "未发现 geosentry-* 容器"
      fi
    else
      err "Docker Desktop 未运行"
    fi
  else
    err "Docker 命令不可用"
  fi

  echo
  info "Git 状态："
  if git rev-parse --abbrev-ref HEAD >/dev/null 2>&1; then
    local branch ahead behind
    branch=$(git rev-parse --abbrev-ref HEAD)
    ahead=$(git rev-list --count @{u}..HEAD 2>/dev/null || echo 0)
    behind=$(git rev-list --count HEAD..@{u} 2>/dev/null || echo 0)
    ok "当前分支：$branch"
    [ "$ahead" -gt 0 ]  && warn "ahead  $ahead 个提交未推送"
    [ "$behind" -gt 0 ] && warn "behind $behind 个远端提交未拉取"
    [ "$ahead" -eq 0 ] && [ "$behind" -eq 0 ] && ok "与 origin/$branch 同步"
  else
    warn "Git 仓库未初始化或异常"
  fi
}

# ─────────────────────────────────────────────
# Doctor — 诊断常见问题
# ─────────────────────────────────────────────
doctor() {
  step "Doctor — 诊断常见问题"

  # 1. Docker
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    ok "Docker Desktop 可用"
  else
    err "Docker Desktop 未启动或命令不可用"
  fi

  # 2. Node.js
  if command -v node >/dev/null 2>&1; then
    local node_v node_maj
    node_v=$(node --version 2>/dev/null | sed 's/v//')
    node_maj=$(echo "$node_v" | cut -d. -f1)
    if [ "$node_maj" -ge 18 ]; then
      ok "Node.js v$node_v (>=18)"
    else
      err "Node.js v$node_v (<18) —— 请升级到 18+"
    fi
  else
    err "Node.js 未安装"
  fi

  # 3. Java
  if command -v java >/dev/null 2>&1; then
    local java_v java_maj
    java_v=$(java -version 2>&1 | head -n1)
    java_maj=$(echo "$java_v" | grep -oE '"[0-9]+' | tr -d '"')
    if [ "${java_maj:-0}" -ge 17 ]; then
      ok "Java $java_v (>=17)"
    else
      err "Java $java_v (<17) —— 后端需要 JDK 17+"
    fi
  else
    err "Java 未安装"
  fi

  # 4. Python
  if command -v python3 >/dev/null 2>&1; then
    local py_v py_maj py_min
    py_v=$(python3 --version 2>&1)
    py_maj=$(echo "$py_v" | grep -oE '[0-9]+' | head -n1)
    py_min=$(echo "$py_v" | grep -oE '[0-9]+' | sed -n '2p')
    if [ "${py_maj:-0}" -ge 3 ] && [ "${py_min:-0}" -ge 10 ]; then
      ok "$py_v (>=3.10)"
    else
      warn "$py_v —— AI 服务建议 3.10+"
    fi
  else
    err "Python3 未安装"
  fi

  # 5. Maven
  if command -v mvn >/dev/null 2>&1; then
    local mvn_v
    mvn_v=$(mvn --version 2>&1 | head -n1)
    ok "$mvn_v"
  else
    err "Maven 未安装 —— 后端构建需要"
  fi

  # 6. 端口冲突快速检查
  echo
  info "关键端口占用："
  for p in 5173 8080 8001 15432 16379 19000; do
    if port_listening "$p"; then
      warn "port $p 被占用"
    else
      ok "port $p 空闲"
    fi
  done

  echo
  info "如需修复常见问题，请参考 docs/DEVELOPER.md §15 与 §28"
}

# ─── 主调度 ───
printf "${C_CYAN}============================================================${C_RESET}\n"
printf "${C_CYAN}  GeoSentry 地脉智巡 · 一键开发启动${C_RESET}\n"
printf "${C_CYAN}============================================================${C_RESET}\n"

case "$CMD" in
  backend)  backend ;;
  frontend) frontend ;;
  ai)       ai ;;
  infra)    infra "${2:-up}" ;;
  all)      all ;;
  status)   status ;;
  doctor)   doctor ;;
  *)        err "未知命令: $CMD"; err "用法: $0 [frontend|backend|ai|infra|all|status|doctor]"; exit 1 ;;
esac
