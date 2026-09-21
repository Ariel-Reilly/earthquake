#!/usr/bin/env bash
# start-dev.sh — 地脉智巡一键全栈启动（Linux / macOS / WSL）
# ================================================================
#
# 用法：
#   ./start-dev.sh                    默认：仅启动前端
#   ./start-dev.sh all               后端 + 前端 + AI 服务（并行后台启动）
#   ./start-dev.sh all --wait        同上，但等全部就绪后报告
#   ./start-dev.sh backend           仅后端（前台）
#   ./start-dev.sh frontend         仅前端（前台）
#   ./start-dev.sh ai               仅 AI 服务（前台）
#   ./start-dev.sh ai --no-reload   生产模式：不加 --reload
#   ./start-dev.sh infra up|down|reset
#   ./start-dev.sh status            健康度速览
#   ./start-dev.sh doctor            诊断常见问题
#   ./start-dev.sh stop              停止所有本地开发服务
#   ./start-dev.sh restart           重启所有本地开发服务
#
# 依赖：Docker / Node.js 18+ / JDK 17+ / Python 3.10+

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CMD="${1:-frontend}"
SUBCMD="${2:-}"
LOG_DIR="${LOG_DIR:-/tmp/disasterguard-logs}"
SPRING_PROFILE="${SPRING_PROFILE:-default}"
# JWT_SECRET 从环境变量读取，不在脚本中硬编码默认值
JWT_SECRET="${JWT_SECRET:-}"

# ─── .env 加载 ───
load_dotenv() {
  local envfile="$PROJECT_ROOT/.env"
  if [[ -f "$envfile" ]]; then
    while IFS='=' read -r key val; do
      [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
      [[ -z "${!key}" ]] && export "$key"="${val//\"/}" && export "$key"="${!key//\'/}"
    done < <(grep -v '^#' "$envfile")
  fi
}
load_dotenv

# ─── 颜色 ───
if [[ -t 1 ]]; then
  C_CYAN='\033[0;36m'; C_GREEN='\033[0;32m'; C_YELLOW='\033[0;33m'
  C_RED='\033[0;31m'; C_BOLD='\033[1m'; C_RESET='\033[0m'
else
  C_CYAN=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_BOLD=""; C_RESET=""
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
    lsof -i ":$port" -sTCP:LISTEN -t >/dev/null 2>&1
  elif command -v ss >/dev/null 2>&1; then
    ss -lnt "sport = :$port" -t >/dev/null 2>&1
  else
    netstat -lnt 2>/dev/null | grep -q ":$port "
  fi
}

wait_port() {
  local port=$1; local name=${2:-port}; local max_wait=${3:-60}
  local i=0
  while (( i < max_wait )); do
    if port_listening "$port" 2>/dev/null; then
      ok "$name :$port 就绪"
      return 0
    fi
    printf "\r      [%02ds] 等待 %s :%s " "$i" "$name" "$port"
    sleep 2; (( i += 2 ))
  done
  echo ""
  warn "$name :$port 超时未就绪"
  return 1
}

wait_all() {
  local max_wait=${1:-120}
  local i=0
  while (( i < max_wait )); do
    local ready=0
    port_listening 8080  2>/dev/null && (( ready++ )) || true
    port_listening 5173  2>/dev/null && (( ready++ )) || true
    port_listening 8001  2>/dev/null && (( ready++ )) || true
    printf "\r      [%02ds] 就绪 %d/3 " "$i" "$ready"
    if (( ready == 3 )); then echo ""; return 0; fi
    sleep 2; (( i += 2 ))
  done
  echo ""
  return 1
}

free_port() {
  local port=$1
  if port_listening "$port" 2>/dev/null; then
    local pid
    pid=$(lsof -ti ":$port" 2>/dev/null || true)
    if [[ -n "$pid" ]]; then
      warn "Port $port 被 PID=$pid，尝试终止..."
      kill -9 "$pid" 2>/dev/null || true
      sleep 1
    fi
  fi
}

# ─── 日志辅助 ───
init_log_dir() {
  mkdir -p "$LOG_DIR"
}

service_log() { echo "$LOG_DIR/$1-$$.log"; }

# ─────────────────────────────────────────────
# A. 基础设施
# ─────────────────────────────────────────────
infra() {
  local action="${SUBCMD:-up}"
  case "$action" in
    up)
      step "启动 Docker 基础设施..."
      cd "$PROJECT_ROOT"
      docker compose up -d postgres redis minio emqx tdengine >/dev/null 2>&1 &
      wait_all 60 || true
      ;;
    down)
      step "停止基础设施..."
      cd "$PROJECT_ROOT" && docker compose down >/dev/null 2>&1
      ok "基础设施已停止"
      ;;
    reset)
      step "销毁并重建基础设施..."
      cd "$PROJECT_ROOT"
      docker compose down -v >/dev/null 2>&1
      docker compose up -d postgres redis minio emqx tdengine >/dev/null 2>&1 &
      wait_all 60 || true
      ;;
    *)
      err "用法: $0 infra up|down|reset"
      exit 1
      ;;
  esac
}

# ─────────────────────────────────────────────
# B. 后端
# ─────────────────────────────────────────────
backend() {
  step "启动后端 (Spring Boot)"

  # 释放端口
  if port_listening 8080 2>/dev/null; then
    local pid
    pid=$(lsof -ti ":8080" 2>/dev/null || true)
    if [[ -n "$pid" ]]; then
      warn "Port 8080 被 PID=$pid，终止..."
      kill -9 "$pid" 2>/dev/null || true; sleep 1
    fi
  fi

  # 检查基础设施
  local infra_ok=true
  for entry in "15432:Postgres" "16379:Redis" "19000:MinIO"; do
    local p="${entry%%:*}"; local n="${entry##*:}"
    if port_listening "$p" 2>/dev/null; then
      ok "$n :$p"
    else
      err "$n :$p 未就绪"
      info "启动: $0 infra up"
      infra_ok=false
    fi
  done
  [[ "$infra_ok" == false ]] && return 1

  export SPRING_PROFILES_ACTIVE="$SPRING_PROFILE"
  export POSTGRES_PORT=15432
  export REDIS_PORT=16379
  export REDIS_HOST=127.0.0.1
  # REDIS_PASSWORD 从环境变量读取，.env 已定义 CHANGE_ME 占位值（开发用）
  export REDIS_PASSWORD="${REDIS_PASSWORD:-CHANGE_ME__dev_redis_password_2026}"
  export MINIO_ENDPOINT=http://127.0.0.1:19000
  # JWT_SECRET 从环境变量读取，不在脚本中硬编码
  if [[ -n "$JWT_SECRET" ]]; then
    export JWT_SECRET="$JWT_SECRET"
  else
    warn "JWT_SECRET 未设置，请通过环境变量或 .env 文件配置"
  fi
  export DEV_USERS_ENABLED=true
  export DEVICE_AUTH_STRICT=false
  export TDENGINE_ENABLED=false
  export EMQX_ENABLED=false
  export AI_ENABLED=false

  info "后端: http://localhost:8080/api"
  info "Swagger: http://localhost:8080/swagger-ui.html"

  cd "$PROJECT_ROOT/backend"
  exec mvn spring-boot:run -DskipTests
}

# ─────────────────────────────────────────────
# C. 前端
# ─────────────────────────────────────────────
frontend() {
  step "启动前端 (Vite)"

  free_port 5173

  if port_listening 8080 2>/dev/null; then
    ok "后端 8080 可达"
  else
    warn "后端 8080 未运行（需单独启动或用 all）"
  fi

  export VITE_USE_BACKEND=true
  export VITE_API_BASE_URL=http://localhost:8080/api
  export VITE_AI_SERVICE_BASE=http://localhost:8001/api/ai

  cd "$PROJECT_ROOT/frontend"
  if [[ ! -d "node_modules" ]]; then
    info "首次运行，安装 npm 依赖..."
    npm install >/dev/null 2>&1
  fi

  info "前端: http://localhost:5173/"
  exec npm run dev
}

# ─────────────────────────────────────────────
# D. AI 服务
# ─────────────────────────────────────────────
ai_service() {
  step "启动 AI 微服务 (FastAPI)"

  free_port 8001

  local vhome="$PROJECT_ROOT/ai-service/.venv"
  local py="$vhome/bin/python3"
  local pip="$vhome/bin/pip"
  local uvicorn="$vhome/bin/uvicorn"
  local req="$PROJECT_ROOT/ai-service/requirements.txt"
  local hash_file="$vhome/.req_hash"

  # 创建虚拟环境
  if [[ ! -d "$vhome" ]]; then
    info "创建 Python 虚拟环境..."
    python3 -m venv "$vhome"
  fi

  # 安装依赖（仅文件变化时）
  local needs_install=false
  if [[ ! -f "$pip" ]]; then
    needs_install=true
  elif [[ -f "$req" ]]; then
    local cur_hash cur_file_hash
    cur_file_hash=$(md5sum "$req" 2>/dev/null | awk '{print $1}')
    cur_hash=$(<"$hash_file" 2>/dev/null || echo "")
    if [[ "$cur_file_hash" != "$cur_hash" ]]; then
      needs_install=true
    fi
  fi

  if [[ "$needs_install" == true ]]; then
    info "安装 AI 依赖..."
    "$py" -m pip install --upgrade pip >/dev/null 2>&1
    "$pip" install -q -r "$req" 2>&1 | grep -v "Requirement already satisfied" || true
    if [[ -f "$req" ]]; then
      md5sum "$req" | awk '{print $1}' > "$hash_file"
    fi
    ok "依赖安装完成"
  fi

  local reload_arg="--reload"
  [[ "${SUBCMD:-}" == "--no-reload" ]] && reload_arg=""

  info "启动 Uvicorn on http://0.0.0.0:8001..."
  info "API 文档: http://localhost:8001/docs"

  cd "$PROJECT_ROOT/ai-service"
  exec "$uvicorn" app.main:app --host 0.0.0.0 --port 8001 $reload_arg
}

# ─────────────────────────────────────────────
# E. 全栈并行启动（核心改进）
# ─────────────────────────────────────────────
all_stack() {
  step "一键全栈并行启动"
  init_log_dir

  # 基础设施
  if ! port_listening 15432 2>/dev/null; then
    info "启动 Docker 基础设施（后台）..."
    cd "$PROJECT_ROOT"
    docker compose up -d postgres redis minio emqx tdengine >/dev/null 2>&1 &
  else
    ok "基础设施已在运行"
  fi

  # 后端
  info "启动后端（后台）..."
  (
    export SPRING_PROFILES_ACTIVE="$SPRING_PROFILE"
    export POSTGRES_PORT=15432
    export REDIS_PORT=16379
    export REDIS_HOST=127.0.0.1
    export REDIS_PASSWORD="${REDIS_PASSWORD:-CHANGE_ME__dev_redis_password_2026}"
    export MINIO_ENDPOINT=http://127.0.0.1:19000
    [[ -n "$JWT_SECRET" ]] && export JWT_SECRET="$JWT_SECRET" || true
    export DEV_USERS_ENABLED=true
    export DEVICE_AUTH_STRICT=false
    export TDENGINE_ENABLED=false
    export EMQX_ENABLED=false
    export AI_ENABLED=false
    cd "$PROJECT_ROOT/backend"
    mvn spring-boot:run -DskipTests > "$(service_log backend)" 2>&1
  ) &

  # AI 服务
  info "启动 AI 服务（后台）..."
  (
    local vhome="$PROJECT_ROOT/ai-service/.venv"
    local py="$vhome/bin/python3"
    local pip="$vhome/bin/pip"
    local uvicorn="$vhome/bin/uvicorn"
    local req="$PROJECT_ROOT/ai-service/requirements.txt"
    if [[ ! -d "$vhome" ]]; then
      python3 -m venv "$vhome"
    fi
    if [[ -f "$req" ]]; then
      "$pip" install -q -r "$req" 2>/dev/null || true
    fi
    cd "$PROJECT_ROOT/ai-service"
    "$uvicorn" app.main:app --host 0.0.0.0 --port 8001 --reload > "$(service_log ai)" 2>&1
  ) &

  # 前端
  info "启动前端（后台）..."
  (
    cd "$PROJECT_ROOT/frontend"
    [[ ! -d "node_modules" ]] && npm install >/dev/null 2>&1 || true
    export VITE_USE_BACKEND=true
    export VITE_API_BASE_URL=http://localhost:8080/api
    export VITE_AI_SERVICE_BASE=http://localhost:8001/api/ai
    npm run dev > "$(service_log frontend)" 2>&1
  ) &

  echo ""
  printf "${C_CYAN}============================================================${C_RESET}\n"
  printf "${C_CYAN}  所有服务已在后台启动${C_RESET}\n"
  printf "${C_CYAN}============================================================${C_RESET}\n"
  printf "    前端:  ${C_GREEN}http://localhost:5173/${C_RESET}\n"
  printf "    后端:  ${C_GREEN}http://localhost:8080/api${C_RESET}\n"
  printf "    AI:    ${C_GREEN}http://localhost:8001/docs${C_RESET}\n"
  printf "\n    日志目录: ${C_YELLOW}%s${C_RESET}\n" "$LOG_DIR"
  printf "${C_CYAN}============================================================${C_RESET}\n"

  if [[ "${SUBCMD:-}" == "--wait" ]]; then
    echo ""
    printf "${C_YELLOW}等待服务就绪（最多 120 秒）...${C_RESET}\n"
    if wait_all 120; then
      printf "\n${C_GREEN}全部就绪！${C_RESET}\n"
      printf "    前端:  http://localhost:5173/\n"
      printf "    后端:  http://localhost:8080/api\n"
      printf "    AI:    http://localhost:8001/docs\n"
    else
      printf "\n${C_YELLOW}部分服务未就绪，请检查日志目录：%s${C_RESET}\n" "$LOG_DIR"
    fi
  fi
}

# ─────────────────────────────────────────────
# F. 停止所有服务
# ─────────────────────────────────────────────
stop_all() {
  step "停止所有开发服务..."
  for port in 8080 5173 8001; do
    if port_listening "$port" 2>/dev/null; then
      local pid
      pid=$(lsof -ti ":$port" 2>/dev/null || true)
      if [[ -n "$pid" ]]; then
        info "终止 PID=$pid (port $port)..."
        kill -9 "$pid" 2>/dev/null || true
      fi
    fi
  done
  # Docker 容器
  info "停止 Docker 容器..."
  cd "$PROJECT_ROOT" && docker compose stop backend frontend ai-service postgres redis minio emqx tdengine >/dev/null 2>&1 || true
  ok "所有服务已停止"
}

# ─────────────────────────────────────────────
# G. 重启
# ─────────────────────────────────────────────
restart_all() {
  stop_all
  sleep 2
  all_stack
}

# ─────────────────────────────────────────────
# H. 健康度速览
# ─────────────────────────────────────────────
status_check() {
  step "健康度速览"

  local entries=(
    "15432:Postgres"
    "16379:Redis"
    "19000:MinIO API"
    "19001:MinIO Console"
    "11883:EMQX MQTT"
    "28083:EMQX Dashboard"
    "16041:TDengine HTTP"
    "5173:Frontend (Vite)"
    "8080:Backend"
    "8001:AI Service"
  )

  for entry in "${entries[@]}"; do
    local p="${entry%%:*}"; local n="${entry##*:}"
    if port_listening "$p" 2>/dev/null; then
      local who=""
      if command -v lsof >/dev/null 2>&1; then
        who=$(lsof -ti ":$p" 2>/dev/null | head -n1)
        [[ -n "$who" ]] && who=" PID=$who" || who=""
      fi
      ok "${n} :${p}${who}"
    else
      err "${n} :${p} DOWN"
    fi
  done

  echo ""
  info "Docker 状态："
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    ok "Docker 在线"
    local containers
    containers=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -E 'disasterguard' || true)
    if [[ -n "$containers" ]]; then
      info "容器: $containers"
    else
      info "未发现 disasterguard-* 容器（正常，如用本地进程启动）"
    fi
  else
    err "Docker Desktop 未运行"
  fi

  echo ""
  info "Git 状态："
  if git rev-parse --abbrev-ref HEAD >/dev/null 2>&1; then
    local branch ahead behind
    branch=$(git rev-parse --abbrev-ref HEAD)
    ahead=$(git rev-list --count @{u}..HEAD 2>/dev/null || echo 0)
    behind=$(git rev-list --count HEAD..@{u} 2>/dev/null || echo 0)
    ok "分支: $branch"
    [[ "$ahead" -gt 0 ]]  && warn "ahead  $ahead 个提交未推送"
    [[ "$behind" -gt 0 ]] && warn "behind $behind 个远端提交未拉取"
    [[ "$ahead" -eq 0 && "$behind" -eq 0 ]] && ok "与 origin/$branch 同步"
  else
    warn "Git 不可用"
  fi
}

# ─────────────────────────────────────────────
# I. Doctor
# ─────────────────────────────────────────────
doctor_check() {
  step "Doctor — 诊断依赖"

  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    ok "Docker Desktop 可用"
  else
    err "Docker Desktop 未运行"
  fi

  if command -v node >/dev/null 2>&1; then
    local node_v node_maj
    node_v=$(node --version 2>/dev/null); node_maj=${node_v:1:2}
    if [[ "${node_maj#0}" -ge 18 ]]; then
      ok "Node.js $node_v (>=18)"
    else
      err "Node.js $node_v (<18) — 需升级到 18+"
    fi
  else
    err "Node.js 未安装"
  fi

  if command -v java >/dev/null 2>&1; then
    local java_v java_maj
    java_v=$(java -version 2>&1 | head -n1)
    java_maj=$(echo "$java_v" | grep -oE '"[0-9]+' | tr -d '"' | head -n1)
    if [[ "${java_maj:-0}" -ge 17 ]]; then
      ok "Java $java_v (>=17)"
    else
      err "Java $java_v (<17) — 后端需 JDK 17+"
    fi
  else
    err "Java 未安装"
  fi

  if command -v python3 >/dev/null 2>&1; then
    local py_v py_maj py_min
    py_v=$(python3 --version 2>&1)
    py_maj=$(echo "$py_v" | grep -oE '[0-9]+' | sed -n '1p')
    py_min=$(echo "$py_v" | grep -oE '[0-9]+' | sed -n '2p')
    if [[ "$py_maj" -ge 3 && "$py_min" -ge 10 ]]; then
      ok "$py_v (>=3.10)"
    else
      warn "$py_v — AI 服务建议 3.10+"
    fi
  else
    err "Python3 未安装"
  fi

  if command -v mvn >/dev/null 2>&1; then
    ok "$(mvn --version 2>&1 | head -n1)"
  else
    err "Maven 未安装"
  fi

  echo ""
  info "关键端口占用："
  for p in 5173 8080 8001 15432 16379 19000; do
    if port_listening "$p" 2>/dev/null; then
      warn "  :$p 被占用"
    else
      ok "  :$p 空闲"
    fi
  done

  echo ""
  info "参考文档：docs/DEVELOPER.md"
}

# ─── 主调度 ───
printf "${C_CYAN}============================================================${C_RESET}\n"
printf "${C_CYAN}  DisasterGuard 地脉智巡 · 一键开发启动${C_RESET}\n"
printf "${C_CYAN}============================================================${C_RESET}\n"

case "$CMD" in
  backend)   backend ;;
  frontend)   frontend ;;
  ai)         ai_service ;;
  all)        all_stack ;;
  infra)      infra ;;
  stop)       stop_all ;;
  restart)    restart_all ;;
  status)     status_check ;;
  doctor)     doctor_check ;;
  *)          err "未知命令: $CMD"
              err "用法: $0 [frontend|backend|ai|all|infra|stop|restart|status|doctor]"
              exit 1 ;;
esac
