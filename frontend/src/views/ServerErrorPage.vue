<script setup>
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  AlertTriangle,
  Home,
  MountainSnow,
  RefreshCw,
  ServerCrash,
} from "lucide-vue-next";
import { getAuthToken } from "../stores/auth";
import { isBackendRuntime } from "../services/dashboardApi";

const route = useRoute();
const router = useRouter();

const retryCountdown = ref(5);
let timer = null;

const errorSource = ref("backend");
const errorMessage = ref(
  typeof route.query.message === "string" ? route.query.message : "",
);

function startCountdown() {
  if (timer) {
    clearInterval(timer);
  }
  retryCountdown.value = 5;
  timer = setInterval(() => {
    retryCountdown.value -= 1;
    if (retryCountdown.value <= 0) {
      clearInterval(timer);
      timer = null;
      retry();
    }
  }, 1000);
}

function retry() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  const from = typeof route.query.from === "string" ? route.query.from : "/dashboard";
  router.replace(from.startsWith("/") ? from : "/dashboard");
}

function backHome() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  router.replace("/dashboard");
}

function copyTraceId() {
  const traceId =
    typeof route.query.traceId === "string"
      ? route.query.traceId
      : `err-${Date.now().toString(36)}`;
  if (navigator?.clipboard) {
    navigator.clipboard
      .writeText(traceId)
      .then(() => {
        // 简单反馈不依赖 toast 组件
        errorMessage.value = `追踪号已复制：${traceId}`;
      })
      .catch(() => {
        errorMessage.value = traceId;
      });
  } else {
    errorMessage.value = traceId;
  }
}

onMounted(() => {
  // 区分是后端 500 还是连接断开；连接断就降级到 mock 模式描述
  const token = getAuthToken();
  if (!token || !isBackendRuntime()) {
    errorSource.value = "connection";
    errorMessage.value =
      errorMessage.value ||
      "无法连接到后端服务。请检查 VITE_API_BASE_URL 配置或后端进程状态。";
  } else {
    errorSource.value = "backend";
    errorMessage.value =
      errorMessage.value || "服务器内部错误。建议稍后重试或联系系统运维。";
  }
  // 自动重试倒计时（5 秒）
  startCountdown();
});

onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
});
</script>

<template>
  <div class="error-shell">
    <div class="error-card">
      <div class="error-illustration">
        <ServerCrash :size="140" :stroke-width="1.4" />
      </div>

      <div class="error-code">500</div>
      <h1 class="error-title">系统开了小差</h1>
      <p class="error-desc">
        <span v-if="errorSource === 'connection'">
          <AlertTriangle :size="14" :stroke-width="2" style="vertical-align: -2px;" />
          检测到后端服务不可达，本次页面以离线模式加载受限。
        </span>
        <span v-else>
          请求处理过程中发生未预期错误，我们已记录问题快照，请稍后重试。
        </span>
      </p>

      <div class="error-meta-block">
        <div class="error-meta">
          <span class="meta-key">可能原因</span>
          <span class="meta-val">
            {{
              errorSource === "connection"
                ? "API 网关超时 / 跨域拦截 / 后端进程退出"
                : "未捕获异常或数据校验失败"
            }}
          </span>
        </div>
        <div v-if="errorMessage" class="error-meta">
          <span class="meta-key">详情</span>
          <span class="meta-val">{{ errorMessage }}</span>
        </div>
        <div class="error-meta">
          <span class="meta-key">本路径</span>
          <code>{{ $route.fullPath }}</code>
        </div>
      </div>

      <div class="error-actions">
        <button class="btn btn-primary" @click="retry">
          <RefreshCw :size="16" />
          立即重试
        </button>
        <button class="btn btn-secondary" @click="backHome">
          <Home :size="16" />
          返回首页
        </button>
        <button class="btn btn-ghost" @click="copyTraceId">
          复制追踪号
        </button>
      </div>

      <p class="error-countdown">
        {{ retryCountdown > 0 ? `${retryCountdown} 秒后自动重试…` : "正在重试…" }}
      </p>

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
  border-top: 4px solid var(--c-warning, #b57c18);
}

.error-code {
  background: linear-gradient(135deg, #d97706 0%, #b45309 100%);
}

.btn-primary {
  background: var(--c-warning, #b57c18);
  color: var(--c-on-brand);
}

.btn-primary:hover {
  background: var(--c-warning-ink);
  transform: translateY(-1px);
  box-shadow: 0 6px 16px -4px rgba(181, 124, 24, 0.4);
}

.btn-secondary {
  background: var(--c-surface);
  color: var(--c-info, #27805a);
  border-color: var(--c-border, #dde8e4);
}

.btn-secondary:hover {
  background: var(--c-info-bg, rgba(29, 107, 154, 0.08));
  border-color: var(--c-info, #27805a);
}

.btn-ghost {
  background: transparent;
  color: var(--c-text-2, var(--c-ink-1));
  border-color: transparent;
}

.btn-ghost:hover {
  background: var(--c-surface-2, #f8fafa);
  color: var(--c-text, #17333b);
}

.error-countdown {
  margin: 20px 0 0;
  font-size: 13px;
  color: #718096;
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
