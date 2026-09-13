<script setup>
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  AlertTriangle,
  Home,
  Mountain,
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
  max-width: 560px;
  background: #ffffff;
  border-radius: 18px;
  box-shadow:
    0 24px 60px -20px rgba(215, 122, 69, 0.18),
    0 8px 24px -8px rgba(43, 108, 176, 0.12);
  padding: 48px 40px 32px;
  text-align: center;
  position: relative;
  overflow: hidden;
}

.error-illustration {
  display: flex;
  justify-content: center;
  color: #d77a45;
  margin-bottom: 8px;
}

.error-code {
  font-size: 88px;
  font-weight: 800;
  letter-spacing: -2px;
  line-height: 1;
  background: linear-gradient(135deg, #d77a45 0%, #b85a32 100%);
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
  margin: 0 0 20px;
  line-height: 1.6;
}

.error-meta-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0 auto 24px;
  max-width: 420px;
}

.error-meta {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 14px;
  background: #fef5f0;
  border: 1px solid #fbd9c6;
  border-radius: 8px;
  font-size: 13px;
  text-align: left;
}

.meta-key {
  color: #9b4a2a;
  flex-shrink: 0;
  font-weight: 500;
  min-width: 64px;
}

.meta-val {
  color: #4a5568;
  word-break: break-word;
}

.error-meta code {
  font-family: ui-monospace, "SFMono-Regular", "Cascadia Code", Menlo, monospace;
  color: #2b6cb0;
  background: rgba(43, 108, 176, 0.08);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  word-break: break-all;
  flex: 1;
  text-align: right;
}

.error-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
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
  background: #d77a45;
  color: #ffffff;
}

.btn-primary:hover {
  background: #b85a32;
  transform: translateY(-1px);
  box-shadow: 0 6px 16px -4px rgba(215, 122, 69, 0.4);
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

.error-countdown {
  margin: 20px 0 0;
  font-size: 13px;
  color: #718096;
}

.error-brand {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 28px;
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
