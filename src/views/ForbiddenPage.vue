<script setup>
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  Home,
  LogIn,
  Mail,
  Mountain,
  ShieldAlert,
} from "lucide-vue-next";
import { getHomePathByRole, getAuthRole, getAuthUser } from "../stores/auth";

const route = useRoute();
const router = useRouter();

const fromPath = computed(() => {
  const v = route.query.from;
  return typeof v === "string" && v.startsWith("/") ? v : "";
});

const reasonLabel = computed(() => {
  switch (route.query.reason) {
    case "no-path":
      return "当前角色未配置此模块的访问路径";
    case "no-permission":
      return "当前角色缺少进入此页面所需的操作权限";
    default:
      return "访问被拒绝";
  }
});

const requiredPermission = computed(() => {
  const v = route.query.required;
  return typeof v === "string" ? v : "";
});

const userRole = computed(() => getAuthRole() ?? "");
const homePath = computed(() => {
  try {
    return getHomePathByRole(userRole.value);
  } catch {
    return "/dashboard";
  }
});

function backToHome() {
  router.replace(homePath.value || "/dashboard");
}

function goBack() {
  if (fromPath.value && fromPath.value !== route.fullPath) {
    router.replace(homePath.value || "/dashboard");
  } else if (window.history.length > 1) {
    router.back();
  } else {
    router.replace(homePath.value || "/dashboard");
  }
}

function requestAccess() {
  // 跳转消息中心并预填申请描述，供运维/管理员开通权限
  router.push({
    path: "/dashboard/message-center",
    query: {
      compose: "true",
      subject: "权限申请",
      body: `请求开通权限：${requiredPermission.value || fromPath.value || "未知模块"}（当前角色：${userRole.value || "未登录"}）`,
    },
  });
}
</script>

<template>
  <div class="error-shell">
    <div class="error-card">
      <div class="error-illustration">
        <ShieldAlert :size="140" :stroke-width="1.4" />
      </div>

      <div class="error-code">403</div>
      <h1 class="error-title">无权访问此页面</h1>
      <p class="error-desc">{{ reasonLabel }}</p>

      <div v-if="requiredPermission" class="error-meta">
        <span class="meta-key">所需权限</span>
        <code>{{ requiredPermission }}</code>
      </div>
      <div v-if="userRole" class="error-meta">
        <span class="meta-key">当前角色</span>
        <code>{{ userRole }}</code>
      </div>
      <div v-if="fromPath && fromPath.startsWith('/dashboard')" class="error-meta">
        <span class="meta-key">原路径</span>
        <code>{{ fromPath }}</code>
      </div>

      <div class="error-actions">
        <button class="btn btn-primary" @click="backToHome">
          <Home :size="16" />
          返回首页
        </button>
        <button class="btn btn-secondary" @click="goBack">
          <ArrowLeft :size="16" />
          上一步
        </button>
        <button class="btn btn-ghost" @click="requestAccess">
          <Mail :size="16" />
          申请权限
        </button>
      </div>

      <div class="error-brand">
        <Mountain :size="14" :stroke-width="2.4" />
        <span>地脉智巡 · GEO-SENTRY v1.7</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.error-shell {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
  background: linear-gradient(135deg, #fef3ec 0%, #f6f8fc 50%, #eef4fb 100%);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
    "Microsoft YaHei", sans-serif;
}

.error-card {
  width: 100%;
  max-width: 520px;
  background: #ffffff;
  border-radius: 18px;
  box-shadow:
    0 24px 60px -20px rgba(226, 125, 91, 0.18),
    0 8px 24px -8px rgba(43, 108, 176, 0.12);
  padding: 48px 40px 32px;
  text-align: center;
  position: relative;
  overflow: hidden;
}

.error-illustration {
  display: flex;
  justify-content: center;
  color: #e27d5b;
  margin-bottom: 8px;
}

.error-code {
  font-size: 88px;
  font-weight: 800;
  letter-spacing: -2px;
  line-height: 1;
  background: linear-gradient(135deg, #e27d5b 0%, #d77a45 50%, #b85a32 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  margin-bottom: 12px;
}

.error-title {
  font-size: 22px;
  font-weight: 600;
  color: #1a202c;
  margin: 0 0 8px;
}

.error-desc {
  font-size: 14px;
  color: #4a5568;
  margin: 0 0 24px;
  line-height: 1.6;
}

.error-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  background: #f7fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  margin: 0 auto 8px;
  max-width: 360px;
  font-size: 13px;
}

.meta-key {
  color: #718096;
  flex-shrink: 0;
}

.error-meta code {
  font-family: ui-monospace, "SFMono-Regular", "Cascadia Code", Menlo, monospace;
  color: #2b6cb0;
  background: #ebf8ff;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  word-break: break-all;
  text-align: right;
}

.error-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 28px;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.18s ease;
  font-family: inherit;
}

.btn-primary {
  background: #e27d5b;
  color: #ffffff;
}

.btn-primary:hover {
  background: #d77a45;
  transform: translateY(-1px);
  box-shadow: 0 6px 16px -4px rgba(226, 125, 91, 0.4);
}

.btn-secondary {
  background: #ffffff;
  color: #2b6cb0;
  border-color: #cbd5e0;
}

.btn-secondary:hover {
  background: #f7fafc;
  border-color: #2b6cb0;
}

.btn-ghost {
  background: transparent;
  color: #4a5568;
  border-color: transparent;
}

.btn-ghost:hover {
  background: #edf2f7;
  color: #2d3748;
}

.error-brand {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 32px;
  padding-top: 16px;
  border-top: 1px dashed #e2e8f0;
  font-size: 12px;
  color: #a0aec0;
  width: 100%;
  justify-content: center;
}

@media (max-width: 520px) {
  .error-card {
    padding: 36px 24px 24px;
  }
  .error-code {
    font-size: 72px;
  }
  .error-title {
    font-size: 20px;
  }
}
</style>
