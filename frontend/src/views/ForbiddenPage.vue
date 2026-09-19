<script setup>
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  Home,
  LogIn,
  Mail,
  MountainSnow,
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
        <MountainSnow :size="14" :stroke-width="2.4" />
        <span>地脉智巡 · GEO-SENTRY v1.7</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* Error page styles now shared in styles.css */
/* Local overrides only */
.error-card {
  border-top: 4px solid var(--c-brand, #2f7f80);
}

.error-code {
  background: linear-gradient(135deg, var(--c-brand, #2f7f80) 0%, #246b6c 100%);
}

.btn-primary {
  background: var(--c-brand, #2f7f80);
  color: #ffffff;
}

.btn-primary:hover {
  background: var(--c-brand-hover, #246b6c);
  transform: translateY(-1px);
  box-shadow: 0 6px 16px -4px rgba(47, 127, 128, 0.4);
}

.btn-secondary {
  background: #ffffff;
  color: var(--c-brand, #2f7f80);
  border-color: var(--c-border, #dde8e4);
}

.btn-secondary:hover {
  background: var(--c-brand-light, #eff9f6);
  border-color: var(--c-brand, #2f7f80);
}

.btn-ghost {
  background: transparent;
  color: var(--c-text-2, #4a6367);
  border-color: transparent;
}

.btn-ghost:hover {
  background: var(--c-surface-2, #f8fafa);
  color: var(--c-text, #17333b);
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
