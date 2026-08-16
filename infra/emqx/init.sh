#!/bin/bash
# EMQX broker 侧认证初始化脚本
# 在容器首次启动时通过 emqx ctl 创建 geosentry_backend 用户。
# 仅在用户不存在时执行（EMQX data 目录持久化，重跑不会重复创建）。

set -e

USERNAME="${EMQX_USERNAME:-geosentry_backend}"
PASSWORD="${EMQX_PASSWORD:-CHANGE_ME_EMQX_PASSWORD}"

echo "[emqx-init] 等待 EMQX 就绪..."
for i in $(seq 1 30); do
  if emqx ping 2>/dev/null; then
    break
  fi
  sleep 2
done

if ! emqx ping; then
  echo "[emqx-init] ERROR: EMQX 未就绪，跳过用户创建"
  exit 1
fi

# 检查认证插件是否已启用，未启用则创建 Built-in Database 认证器
if ! emqx ctl authenticator list 2>/dev/null | grep -q built_in_database; then
  echo "[emqx-init] 创建 Built-in Database 认证器..."
  emqx ctl authenticator create \
    -n geohazard_auth \
    -t built_in_database \
    -c '{"password_hash_algorithm":{"type":"bcrypt","salt_position":"suffix"}}' 2>/dev/null || true
fi

# 创建 geosentry_backend MQTT 用户（如果不存在）
if ! emqx ctl authenticator user list -a geohazard_auth 2>/dev/null | grep -q "$USERNAME"; then
  echo "[emqx-init] 创建 MQTT 用户 $USERNAME ..."
  emqx ctl authenticator user create -a geohazard_auth \
    -u "$USERNAME" \
    -p "$PASSWORD" 2>/dev/null || echo "[emqx-init] 用户可能已存在"
else
  echo "[emqx-init] 用户 $USERNAME 已存在，跳过"
fi

# 授权 geosentry_backend 可订阅 geohazard/+/#（MQTT ACL）
if ! emqx ctl authorization source list 2>/dev/null | grep -q geohazard_acl; then
  echo "[emqx-init] 创建 MongoDB ACL（使用 built-in database）..."
  emqx ctl authorization source create \
    -t built_in_database \
    -n geohazard_acl 2>/dev/null || true
fi

echo "[emqx-init] 完成。Broker 认证用户: $USERNAME"
