#!/bin/bash
# EMQX broker 启动包装脚本
#
# 历史沿革（很重要，别改回去）：
#   本脚本最初的设计是「阶段1 等待 EMQX 就绪 → 阶段2 用 emqx ctl 建认证器/用户/ACL
#   → 阶段3 exec 启动 EMQX 主进程」。但阶段3 才是启动 broker 的那一步，所以阶段1 的
#   `emqx ping` 在**任何情况下都不可能成功**，每次容器启动都白等满 60 秒；
#   阶段2 更是必然失败——EMQX 5.9.0 的 `emqx ctl` 根本没有 authenticator /
#   authorization 子命令（实测只有 `authz cache-clean` 与 `conf load`），
#   而这些失败又被 `|| true` 和 `2>/dev/null` 吞掉，脚本照样打印"✓ 完成"。
#
# 另外：本文件曾以 CRLF 检出，在 Linux 容器里 bash 直接
# `syntax error near unexpected token $'do\r'` 死在第 18 行，从未执行到启动 broker
# 的那一行 —— 这就是 EMQX 容器无限重启的根因。仓库根 .gitattributes 已强制
# `*.sh text eol=lf`，不要再让它变回 CRLF。
#
# 现在的做法：先做能力探测（不需要 broker 在运行，`emqx ctl` 无参数只打印用法），
# 只有 CLI 真的支持 authn 时才先起 broker 再初始化；否则如实告警，不等待、不撒谎。

set -e

# R3 P0-3：回退值必须与 docker-compose.yml 中「emqx 服务」和「backend 服务」
# 使用的默认值完全一致。此前本脚本回退 CHANGE_ME_EMQX_PASSWORD，与后端持有的
# CHANGE_ME_emqx_secure_password_2024 不等 → MQTT 鉴权必然失败。
USERNAME="${EMQX_USERNAME:-disasterguard_backend}"
PASSWORD="${EMQX_PASSWORD:-CHANGE_ME_emqx_secure_password_2024}"
MAX_RETRIES=3
RETRY_INTERVAL=5

# ── 能力探测：`emqx ctl` 在 5.9.0 上没有任何与认证器相关的子命令 ────────────
_emqx_cli_supports_authn() {
  emqx ctl 2>&1 | grep -qE '^[[:space:]]*authn\b'
}

do_init() {
  if ! emqx ctl authn list 2>/dev/null | grep -q built_in_database; then
    echo "[emqx-init] 创建 Built-in Database 认证器..."
    emqx ctl authn create \
      -n disasterguard_auth \
      -t built_in_database \
      -c '{"password_hash_algorithm":{"type":"bcrypt","salt_position":"suffix"}}'
  fi

  if ! emqx ctl authn user list -a disasterguard_auth 2>/dev/null | grep -q "$USERNAME"; then
    echo "[emqx-init] 创建 MQTT 用户 $USERNAME ..."
    emqx ctl authn user create -a disasterguard_auth \
      -u "$USERNAME" -p "$PASSWORD"
  else
    echo "[emqx-init] 用户 $USERNAME 已存在，跳过"
  fi

  if ! emqx ctl authz list 2>/dev/null | grep -q disasterguard_acl; then
    echo "[emqx-init] 创建 ACL 源..."
    emqx ctl authz create -t built_in_database -n disasterguard_acl
  fi
}

if _emqx_cli_supports_authn; then
  # ⚠️ 本分支在当前使用的 EMQX 5.9.0 上**不可达**（`emqx ctl` 无 authn 子命令），
  #    因此未经实机验证。它的存在是为了避免"未来版本支持 CLI 后静默不配置认证"。
  #    若某天它被激活，请先与 docs/CODE_REVIEW_R4.md 里已验证的声明式方案核对，
  #    并注意 authorization 源为 type=file 时 path 是必填项。
  echo "[emqx-init] 检测到 CLI 支持 authn，将先启动 broker 再初始化认证/ACL"
  /usr/bin/docker-entrypoint.sh /opt/emqx/bin/emqx foreground &
  EMQX_PID=$!

  _term() {
    kill -TERM "$EMQX_PID" 2>/dev/null || true
    wait "$EMQX_PID" 2>/dev/null || true
    exit 0
  }
  trap _term TERM INT QUIT

  for _ in $(seq 1 30); do
    emqx ping >/dev/null 2>&1 && break
    sleep 2
  done

  if emqx ping >/dev/null 2>&1; then
    attempt=1
    while [ "$attempt" -le "$MAX_RETRIES" ]; do
      echo "[emqx-init] 初始化尝试 $attempt/$MAX_RETRIES"
      if do_init 2>&1; then
        echo "[emqx-init] ✓ 完成。Broker 认证用户: $USERNAME"
        break
      fi
      echo "[emqx-init] 尝试 $attempt 失败，重试..."
      sleep "$RETRY_INTERVAL"
      attempt=$((attempt + 1))
    done
  else
    echo "[emqx-init] WARNING: broker 未在 60s 内就绪，跳过认证初始化"
  fi

  wait "$EMQX_PID"
  exit $?
fi

# ── 当前 EMQX 5.9.0 的实际路径：如实告警，然后直接启动 broker ──────────────
echo "[emqx-init] WARNING: 当前 EMQX 版本的 CLI 不支持管理认证器（emqx ctl 无 authn 子命令）"
echo "[emqx-init] WARNING: 跳过认证/ACL 初始化 —— MQTT 当前为默认放行（allow-all）"
echo "[emqx-init] WARNING: EMQX_USERNAME/EMQX_PASSWORD 不参与鉴权，详见 docs/CODE_REVIEW_R4.md"
echo "[emqx-init] ⚠ 认证初始化被跳过 —— 不要假定 MQTT 已鉴权（见上方 WARNING）"
echo "[emqx-init] 已验证的开启方式（环境变量声明式认证）见 docs/CODE_REVIEW_R4.md 的 EMQX 小节"

# ── 启动 EMQX 主进程 ──────────────────────────────────────────────────────
# exec 替换当前 bash 为 EMQX，保持 PID=1，SIGTERM 等信号正常传递
# 必须显式传递 EMQX 的启动命令，因为 docker-entrypoint.sh 依赖 "$@"
exec /usr/bin/docker-entrypoint.sh /opt/emqx/bin/emqx foreground
