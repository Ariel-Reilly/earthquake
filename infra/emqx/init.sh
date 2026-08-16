#!/bin/bash
# EMQX broker 侧认证初始化脚本
# 在容器首次启动时通过 emqx ctl 创建 geosentry_backend 用户。
# 仅在用户不存在时执行（EMQX data 目录持久化，重跑不会重复创建）。
# 失败时自动重试 3 次，全部失败则以非零退出码终止（阻断 broker 正常运行）。

set -e

USERNAME="${EMQX_USERNAME:-geosentry_backend}"
PASSWORD="${EMQX_PASSWORD:-CHANGE_ME_EMQX_PASSWORD}"
MAX_RETRIES=3
RETRY_INTERVAL=5

echo "[emqx-init] USERNAME=$USERNAME, PASSWORD_SET=$([ -n "$PASSWORD" ] && echo yes || echo no)"
echo "[emqx-init] 等待 EMQX 就绪..."

# 等待 broker 就绪（最多 60s）
for i in $(seq 1 30); do
  if emqx ping 2>/dev/null; then
    echo "[emqx-init] EMQX 已就绪"
    break
  fi
  sleep 2
done

if ! emqx ping 2>/dev/null; then
  echo "[emqx-init] ERROR: EMQX 未在 60s 内就绪，中止初始化"
  exit 1
fi

do_init() {
  # 检查认证插件是否已启用，未启用则创建 Built-in Database 认证器
  if ! emqx ctl authenticator list 2>/dev/null | grep -q built_in_database; then
    echo "[emqx-init] 创建 Built-in Database 认证器..."
    emqx ctl authenticator create \
      -n geohazard_auth \
      -t built_in_database \
      -c '{"password_hash_algorithm":{"type":"bcrypt","salt_position":"suffix"}}' 2>/dev/null
  fi

  # 创建 geosentry_backend MQTT 用户（如果不存在）
  if ! emqx ctl authenticator user list -a geohazard_auth 2>/dev/null | grep -q "$USERNAME"; then
    echo "[emqx-init] 创建 MQTT 用户 $USERNAME ..."
    emqx ctl authenticator user create -a geohazard_auth \
      -u "$USERNAME" \
      -p "$PASSWORD" 2>/dev/null
  else
    echo "[emqx-init] 用户 $USERNAME 已存在，跳过"
  fi

  # ACL 授权
  if ! emqx ctl authorization source list 2>/dev/null | grep -q geohazard_acl; then
    echo "[emqx-init] 创建 ACL 源..."
    emqx ctl authorization source create \
      -t built_in_database \
      -n geohazard_acl 2>/dev/null
  fi
}

# 带重试执行初始化
attempt=1
while [ $attempt -le $MAX_RETRIES ]; do
  echo "[emqx-init] 初始化尝试 $attempt/$MAX_RETRIES"
  if do_init 2>&1; then
    echo "[emqx-init] ✓ 完成。Broker 认证用户: $USERNAME"
    exit 0
  fi
  echo "[emqx-init] 尝试 $attempt 失败，等待 ${RETRY_INTERVAL}s 后重试..."
  sleep $RETRY_INTERVAL
  attempt=$((attempt + 1))
done

echo "[emqx-init] ERROR: $MAX_RETRIES 次尝试均失败，broker 将以无认证状态运行！"
echo "[emqx-init] 请检查 EMQX_DASHBOARD__DEFAULT_USERNAME/PASSWORD 是否正确，"
echo "[emqx-init] 或手动进入容器执行 emqx ctl authenticator create ..."
# 关键：非零退出码让 entrypoint 报错，阻断 broker 正常运行
exit 1
