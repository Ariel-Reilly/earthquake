/**
 * 通知渠道配置 — /dashboard/notification-channels
 *
 * 功能概述:
 *   - 渠道列表管理（短信 / APP 推送 / 邮件 / LED 屏 / Webhook）
 *   - 渠道配置（API Key / 签名 / 限流 / 模板绑定）
 *   - 连接测试
 *   - 渠道优先级 + 失败降级策略
 *   - 发送日志查询
 *
 * 权限: notice:view
 *
 * @author Cursor
 */

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import {
  Radar,
  AlertCircle,
  Bell,
  BellRing,
  CheckCircle2,
  ChevronDown,
  Edit,
  ExternalLink,
  Globe,
  Loader2,
  Mail,
  MessageSquare,
  Monitor,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  Smartphone,
  Trash2,
  Volume2,
  Webhook,
  XCircle,
} from "lucide-vue-next";
import {
  getChannelConfigs,
  updateChannelConfig,
  testSend,
  getNotificationRecords,
} from "../../services/_architecture/domain/notificationService";
import { USE_BACKEND } from "../../services/dashboardApi";
import { hasPermission, getAuthUser } from "../../stores/auth";
import ConfirmDialog from "../../components/common/ConfirmDialog.vue";

const emit = defineEmits(["notify"]);

const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" };
const has = (perm) => hasPermission(operator.role, perm);

const loading = ref(false);
const activeTab = ref("sms");
const searchTerm = ref("");
const backendMode = USE_BACKEND;

/**
 * 前端渠道类型 → 后端规范渠道码。
 * 后端 NotificationRouter 只识别：SMS / APP / SCREEN / SOUND_LIGHT / EMAIL / WEBHOOK。
 * 注意 "led" 映射到 SCREEN、"sound" 映射到 SOUND_LIGHT，不能直接取 type。
 */
const CHANNEL_CODE = {
  sms: "SMS",
  app: "APP",
  email: "EMAIL",
  led: "SCREEN",
  webhook: "WEBHOOK",
  sound: "SOUND_LIGHT",
};
const CODE_TO_TYPE = Object.fromEntries(
  Object.entries(CHANNEL_CODE).map(([type, code]) => [code, type])
);

// ── 渠道类型配置 ──
const channelTypes = [
  {
    id: "sms",
    label: "短信",
    icon: MessageSquare,
    color: "#6366f1",
    desc: "通过短信网关发送通知",
    fields: [
      { key: "endpoint", label: "网关地址", placeholder: "https://sms-api.example.com/send" },
      { key: "apiKey", label: "API Key", placeholder: "sk-xxxx" },
      { key: "signName", label: "短信签名", placeholder: "【地脉智巡】" },
      { key: "templateId", label: "模板 ID", placeholder: "SMS_xxxx" },
      { key: "rateLimit", label: "限流（条/分钟）", placeholder: "100" },
    ],
  },
  {
    id: "app",
    label: "APP 推送",
    icon: Smartphone,
    color: "#22c55e",
    desc: "移动端实时推送",
    fields: [
      { key: "fcmKey", label: "FCM Server Key", placeholder: "" },
      { key: "huaweiAppId", label: "华为 App ID", placeholder: "" },
      { key: "xiaomiAppKey", label: "小米 App Key", placeholder: "" },
      { key: "channelId", label: "通知渠道 ID", placeholder: "disasterguard_alert" },
      { key: "importance", label: "推送优先级", placeholder: "HIGH", options: ["HIGH", "NORMAL", "LOW"] },
    ],
  },
  {
    id: "email",
    label: "邮件",
    icon: Mail,
    color: "#87b096",
    desc: "SMTP 邮件发送",
    fields: [
      { key: "smtpHost", label: "SMTP 服务器", placeholder: "smtp.example.com" },
      { key: "smtpPort", label: "SMTP 端口", placeholder: "587" },
      { key: "username", label: "用户名", placeholder: "noreply@disasterguard.cn" },
      { key: "password", label: "密码/App Password", placeholder: "" },
      { key: "fromAddress", label: "发件人地址", placeholder: "noreply@disasterguard.cn" },
      { key: "fromName", label: "发件人名称", placeholder: "地脉智巡" },
    ],
  },
  {
    id: "led",
    label: "LED 屏",
    icon: Monitor,
    color: "#f59e0b",
    desc: "指挥大厅 LED 大屏推送",
    fields: [
      { key: "endpoint", label: "LED 控制器地址", placeholder: "http://192.168.1.100:8080" },
      { key: "screenId", label: "屏幕编号", placeholder: "LED-01" },
      { key: "apiKey", label: "控制 API Key", placeholder: "" },
      { key: "template", label: "显示模板", placeholder: "default" },
    ],
  },
  {
    id: "webhook",
    label: "Webhook",
    icon: Webhook,
    color: "#8b5cf6",
    desc: "HTTP 回调通知第三方系统",
    fields: [
      { key: "url", label: "Webhook URL", placeholder: "https://third-party.example.com/webhook" },
      { key: "secret", label: "签名密钥", placeholder: "用于 HMAC 签名" },
      { key: "method", label: "请求方法", placeholder: "POST", options: ["POST", "PUT"] },
      { key: "headers", label: "自定义 Header", placeholder: "Content-Type: application/json" },
    ],
  },
  {
    id: "sound",
    label: "声光报警",
    icon: Volume2,
    color: "#ef4444",
    desc: "现场声光报警器触发",
    fields: [
      { key: "deviceIp", label: "设备 IP", placeholder: "192.168.1.200" },
      { key: "devicePort", label: "端口", placeholder: "9000" },
      { key: "protocol", label: "协议", placeholder: "TCP" },
      { key: "soundPattern", label: "声音模式", placeholder: "urgent" },
    ],
  },
];

// ── 渠道列表（由后端 channel-config 派生） ──
// 后端数据模型：notification_channel_config 以「灾种 × 级别」为一行，
// 行内 channels 是 ChannelSetting[]{channel, enabled, priority, templateId} 列表。
// 本页按渠道聚合展示，故将多行配置展开为「渠道 → 配置项」的映射再渲染。
const channels = ref([]);

// 原始配置行（保留 id / disasterType / level，保存回写时需要）
const configRows = ref([]);

/**
 * 将后端多行渠道配置展开为按渠道聚合的展示结构。
 * 同一渠道被多个「灾种×级别」引用时，取优先级最高的那条作为卡片主信息。
 */
function buildChannelsFromConfigs(rows) {
  const byType = new Map();
  for (const row of rows) {
    for (const setting of row.channels ?? []) {
      const type = CODE_TO_TYPE[setting.channel];
      // 后端可能返回前端未定义的渠道码，跳过而不是伪造一条陌生卡片
      if (!type) continue;
      const existing = byType.get(type);
      if (!existing || (setting.priority ?? 99) < (existing.priority ?? 99)) {
        byType.set(type, {
          id: `${row.id ?? "new"}-${type}`,
          configRowId: row.id ?? null,
          disasterType: row.disasterType,
          level: row.level,
          type,
          name: `${channelLabel(type)}通道${row.level ? ` · ${row.level}` : ""}`,
          enabled: setting.enabled ?? row.enabled ?? false,
          priority: setting.priority ?? 1,
          health: setting.enabled === false ? "offline" : "healthy",
          healthLabel: setting.enabled === false ? "已停用" : "正常",
          templateId: setting.templateId ?? "",
          fallbackTo: null,
          config: row.templateMapping ?? {},
        });
      }
    }
  }
  return [...byType.values()].sort((a, b) => a.priority - b.priority);
}

function channelLabel(type) {
  return channelTypes.find((t) => t.id === type)?.label ?? type;
}

/**
 * 加载渠道配置。后端模式下走真实接口；非后端模式给出明确提示而非塞假数据。
 */
async function loadChannels() {
  loading.value = true;
  try {
    const rows = await getChannelConfigs();
    configRows.value = Array.isArray(rows) ? rows : [];
    channels.value = buildChannelsFromConfigs(configRows.value);
  } catch (e) {
    channels.value = [];
    configRows.value = [];
    emit(
      "notify",
      backendMode
        ? `渠道配置加载失败：${e.message ?? e}`
        : "未连接后端，无法读取渠道配置"
    );
  } finally {
    loading.value = false;
  }
}

const filteredChannels = computed(() => {
  if (!searchTerm.value.trim()) return channels.value;
  const k = searchTerm.value.toLowerCase();
  return channels.value.filter(
    (c) =>
      c.name.toLowerCase().includes(k) ||
      channelTypes.find((t) => t.id === c.type)?.label.toLowerCase().includes(k)
  );
});

const tabChannels = computed(() =>
  filteredChannels.value.filter((c) => c.type === activeTab.value)
);

// 当前标签页配置
const activeChannelType = computed(() =>
  channelTypes.find((t) => t.id === activeTab.value) ?? channelTypes[0]
);

// ── 发送日志（后端 notification/records） ──
const logs = ref([]);

// 这里是 notification_record.status 的取值域（不是 SendResultDto.status）。
// 未知状态一律标「未知」：既不默认显示成成功，也不把「已读 / 已送达」误标成未投递。
const STATUS_LABEL = {
  SENT: "success",
  DELIVERED: "success",
  SUCCESS: "success",
  FAILED: "failed",
  PARTIAL: "failed",
  BLOCKED: "blocked",
  NO_CHANNEL: "blocked",
  NO_SENDER: "blocked",
  RATE_LIMITED: "blocked",
  PENDING: "pending",
  READ: "read",
  // SENDING/RETRYING 是「流程进行中」，不是「演示发送」——不能和 MOCK_SENT 混为一类。
  SENDING: "sending",
  RETRYING: "sending",
  MOCK_SENT: "demo",
};

const STATUS_TEXT = {
  success: "成功",
  failed: "失败",
  blocked: "已拦截",
  demo: "演示/未投递",
  pending: "待发送",
  sending: "发送中",
  read: "已读",
  unknown: "未知",
};

function statusNote(status) {
  const kind = STATUS_LABEL[status] ?? "unknown";
  if (kind === "failed") return "发送失败";
  if (kind === "blocked") return "已拦截·未投递";
  if (kind === "demo") return "演示发送·未真实投递";
  if (kind === "pending") return "待发送";
  if (kind === "sending") return "发送中，结果待确认";
  if (kind === "read") return "已读";
  if (kind === "unknown") return `状态未登记：${status ?? "—"}`;
  return "";
}

async function loadLogs() {
  try {
    const page = await getNotificationRecords({ page: 1, size: 50 });
    const records = page?.records ?? (Array.isArray(page) ? page : []);
    logs.value = records.map((r) => ({
      id: r.id,
      channel: (r.channels ?? []).map((c) => channelLabel(CODE_TO_TYPE[c] ?? c)).join("/") || "—",
      recipient: r.createdBy ?? "—",
      template: r.title ?? "—",
      status: STATUS_LABEL[r.status] ?? "unknown",
      sentAt: r.createdAt ? new Date(r.createdAt).toLocaleTimeString("zh-CN", { hour12: false }) : "—",
      errorMsg: statusNote(r.status),
    }));
  } catch {
    // 日志是次要信息，失败就留空，不弹错也不塞假数据
    logs.value = [];
  }
}

// ── 状态 ──
const editDrawer = ref(false);
const editingChannel = ref(null);
const testing = ref({});
const saving = ref(false);
const confirmDialogRef = ref(null);

// ── 操作 ──
function openCreate() {
  editingChannel.value = {
    id: null,
    configRowId: null,
    disasterType: null,
    level: null,
    type: activeTab.value,
    name: "",
    enabled: true,
    priority: channels.value.filter((c) => c.type === activeTab.value).length + 1,
    templateId: "",
    config: {},
    fallbackTo: null,
  };
  editDrawer.value = true;
}

function openEdit(channel) {
  editingChannel.value = { ...channel, config: { ...channel.config }, templateId: channel.templateId ?? "" };
  editDrawer.value = true;
}

async function testConnection(channel) {
  testing.value[channel.id] = true;
  try {
    const code = CHANNEL_CODE[channel.type] ?? channel.type;
    const result = await testSend({
      channel: code,
      target: "",
      title: "渠道探测",
      content: "这是一条来自 DisasterGuard 的测试消息",
    });
    // 后端返回的是 SendResultDto.status，没有 success 字段；按状态如实提示。
    // PROBE_ONLY 只确认「发送器已注册」，不校验渠道配置或外部网关连通性，不能报「已校验」。
    const status = result?.status;
    if (status === "BLOCKED") {
      emit("notify", "已拦截：当前仅允许发送给项目测试成员");
    } else if (status === "PROBE_ONLY") {
      emit("notify", `${channel.name} 发送器已注册；未投递任何消息，未校验外部网关`);
    } else if (status === "MOCK_SENT") {
      emit("notify", `${channel.name} 演示发送已受理，未真实投递`);
    } else if (status === "SUCCESS") {
      emit("notify", `${channel.name} 连接测试成功`);
    } else {
      emit("notify", `测试失败：${result?.message ?? status ?? "通道返回失败"}`);
    }
  } catch (e) {
    emit("notify", `测试失败：${e.message ?? e}`);
  } finally {
    testing.value[channel.id] = false;
  }
}

async function toggleChannel(channel) {
  const next = !channel.enabled;
  try {
    await persistChannel({ ...channel, enabled: next });
    channel.enabled = next;
    channel.health = next ? "healthy" : "offline";
    channel.healthLabel = next ? "正常" : "已停用";
    emit("notify", next ? "渠道已启用" : "渠道已停用");
  } catch (e) {
    emit("notify", `操作失败：${e.message ?? e}`);
  }
}

/**
 * 将前端渠道卡片回写为后端 ChannelConfigDto（灾种 × 级别 行结构）。
 * 后端契约：{ id, disasterType, level, channels:[{channel,enabled,priority,templateId}], templateMapping, enabled }
 */
async function persistChannel(channel) {
  const row = configRows.value.find((r) => r.id === channel.configRowId);
  const code = CHANNEL_CODE[channel.type] ?? channel.type;

  // 同一行内的其他渠道设置需保留，只替换当前渠道那一项
  const others = (row?.channels ?? []).filter((s) => s.channel !== code);
  const payload = {
    id: channel.configRowId ?? null,
    disasterType: row?.disasterType ?? channel.disasterType ?? null,
    level: row?.level ?? channel.level ?? null,
    channels: [
      ...others,
      {
        channel: code,
        enabled: channel.enabled,
        priority: channel.priority,
        templateId: channel.templateId ?? "",
      },
    ],
    templateMapping: row?.templateMapping ?? channel.config ?? {},
    enabled: channel.enabled,
  };
  return updateChannelConfig(payload);
}

async function saveChannel() {
  saving.value = true;
  try {
    await persistChannel(editingChannel.value);
    editDrawer.value = false;
    emit("notify", "渠道配置已保存");
    await loadChannels();
  } catch (e) {
    emit("notify", `保存失败：${e.message ?? e}`);
  } finally {
    saving.value = false;
  }
}

async function deleteChannel(channel) {
  const ok = await confirmDialogRef.value?.show({
    title: '删除渠道',
    message: `确认删除渠道「${channel.name}」？`,
    confirmText: '删除',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    // 后端无删除端点，语义上以「禁用」实现下线，避免伪造前端删除
    await persistChannel({ ...channel, enabled: false });
    emit("notify", "渠道已停用");
    await loadChannels();
  } catch (e) {
    emit("notify", `操作失败：${e.message ?? e}`);
  }
}

onMounted(async () => {
  await loadChannels();
  await loadLogs();
});

function goToTemplates() {
  window.location.href = "/dashboard/notice-templates";
}

// ── 样式 ──
const healthStyle = (h) =>
  h === "healthy" ? { bg: "#dcfce7", color: "#166534" } :
  h === "degraded" ? { bg: "#fef9c3", color: "#854d0e" } :
  { bg: "#f1f6f2", color: "#6b7f7c" };

const statusStyle = (s) =>
  s === "success" ? { bg: "#dcfce7", color: "#166534" } :
  s === "demo" || s === "pending" || s === "unknown" ? { bg: "#f1efe8", color: "#6b6250" } :
  s === "read" || s === "sending" ? { bg: "#e8eef0", color: "#4a6154" } :
  { bg: "#fee2e2", color: "#991b1b" };

const channelIcon = (type) => {
  const t = channelTypes.find((c) => c.id === type);
  return t?.icon ?? Bell;
};

const channelColor = (type) => {
  const t = channelTypes.find((c) => c.id === type);
  return t?.color ?? "#6b7f7c";
};
</script>

<template>
  <div class="channels-page">
    <div class="page-header">
      <div class="header-actions">
        <button class="btn-secondary" @click="goToTemplates">
          <BellRing :size="15" />
          通知模板
        </button>
        <button class="btn-primary" @click="openCreate" v-if="has('notice:view')">
          <Plus :size="15" />
          新增渠道
        </button>
      </div>
    </div>

    <!-- 渠道类型标签 -->
    <div class="channel-tabs">
      <button
        v-for="ct in channelTypes"
        :key="ct.id"
        class="channel-tab"
        :class="{ active: activeTab === ct.id }"
        :style="activeTab === ct.id ? { borderColor: ct.color, color: ct.color } : {}"
        @click="activeTab = ct.id"
      >
        <component :is="ct.icon" :size="15" />
        {{ ct.label }}
        <span class="tab-count">{{ channels.filter(c => c.type === ct.id).length }}</span>
      </button>
    </div>

    <!-- 搜索 + 统计 -->
    <div class="toolbar">
      <div class="search-wrap">
        <Search :size="14" class="search-icon" />
        <input v-model="searchTerm" class="search-input" placeholder="搜索渠道名称…" />
      </div>
      <div class="channel-summary">
        <span
          v-for="ct in channelTypes"
          :key="ct.id"
          class="summary-chip"
          :style="{ borderColor: ct.color + '40', color: ct.color }"
        >
          <component :is="ct.icon" :size="12" />
          {{ ct.label }}: {{ channels.filter(c => c.type === ct.id && c.enabled).length }} 启用
        </span>
      </div>
    </div>

    <div class="page-body">
      <!-- 渠道列表 -->
      <div class="channels-grid">
        <div
          v-for="ch in tabChannels"
          :key="ch.id"
          class="channel-card"
          :class="{ disabled: !ch.enabled }"
        >
          <div class="card-header">
            <div class="card-icon" :style="{ background: channelColor(ch.type) + '20', color: channelColor(ch.type) }">
              <component :is="channelIcon(ch.type)" :size="20" />
            </div>
            <div class="card-info">
              <div class="card-name">{{ ch.name }}</div>
              <div class="card-meta">
                <span class="health-badge" :style="healthStyle(ch.health)">{{ ch.healthLabel }}</span>
                <span class="priority-badge">优先级 {{ ch.priority }}</span>
              </div>
            </div>
            <div class="card-toggle">
              <button
                class="toggle-btn"
                :class="{ on: ch.enabled }"
                @click="toggleChannel(ch)"
                :title="ch.enabled ? '停用' : '启用'"
              >
                <div class="toggle-thumb" />
              </button>
            </div>
          </div>

          <!-- 核心配置 -->
          <div class="card-configs">
            <div
              v-for="(val, key) in ch.config"
              :key="key"
              class="config-item"
            >
              <span class="config-key">{{ key }}</span>
              <span class="config-val">{{ key.toLowerCase().includes('key') || key.toLowerCase().includes('pass') || key.toLowerCase().includes('secret') ? '••••' : val }}</span>
            </div>
          </div>

          <div class="card-stats">
            <div class="stat-item">
              <span class="stat-label">渠道编码</span>
              <span class="stat-val">{{ CHANNEL_CODE[ch.type] ?? ch.type }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">适用范围</span>
              <span class="stat-val">{{ ch.level ? `${ch.disasterType ?? "全部"} / ${ch.level}` : "全部" }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">模板</span>
              <span class="stat-val">{{ ch.templateId || "—" }}</span>
            </div>
          </div>

          <div class="card-actions">
            <button class="btn-test" @click="testConnection(ch)" :disabled="testing[ch.id]">
              <Loader2 v-if="testing[ch.id]" :size="13" class="spinning" />
              <Send v-else :size="13" />
              {{ testing[ch.id] ? "测试中…" : "测试" }}
            </button>
            <button class="btn-edit" @click="openEdit(ch)" v-if="has('notice:view')">
              <Edit :size="13" /> 配置
            </button>
            <button class="btn-delete" @click="deleteChannel(ch)" v-if="has('notice:view')">
              <Trash2 :size="13" />
            </button>
          </div>
        </div>

        <div v-if="!tabChannels.length" class="empty-state">
          <Loader2 v-if="loading" :size="40" class="spinning" />
          <Bell v-else :size="40" />
          <p v-if="loading">正在加载渠道配置…</p>
          <p v-else-if="!backendMode">未连接后端，无法读取渠道配置</p>
          <p v-else>该类型暂无渠道配置</p>
          <button v-if="!loading && backendMode" class="btn-primary" @click="openCreate">
            <Plus :size="15" /> 添加第一个渠道
          </button>
        </div>
      </div>

      <!-- 发送日志 -->
      <div class="logs-section">
        <h3 class="logs-title">
          <Radar :size="15" />
          发送日志
          <span class="logs-hint">实时显示最近 50 条</span>
        </h3>
        <div class="logs-table-wrap">
          <table class="logs-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>渠道</th>
                <th>接收方</th>
                <th>模板</th>
                <th>状态</th>
                <th>备注</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="log in logs" :key="log.id">
                <td class="log-time">{{ log.sentAt }}</td>
                <td>{{ log.channel }}</td>
                <td>{{ log.recipient }}</td>
                <td>{{ log.template }}</td>
                <td>
                  <span class="status-badge" :style="statusStyle(log.status)">
                    <CheckCircle2 v-if="log.status === 'success'" :size="11" />
                    <XCircle v-else-if="log.status === 'failed' || log.status === 'blocked'" :size="11" />
                    {{ STATUS_TEXT[log.status] ?? "未知" }}
                  </span>
                </td>
                <td class="log-error">{{ log.errorMsg }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- 编辑抽屉 -->
    <div v-if="editDrawer" class="drawer-overlay" @click.self="editDrawer = false">
      <div class="drawer">
        <div class="drawer-header">
          <h3>{{ editingChannel.id ? "编辑渠道" : "新增渠道" }}</h3>
          <button class="btn-close" @click="editDrawer = false">
            <XCircle :size="18" />
          </button>
        </div>

        <div class="drawer-body">
          <div class="form-field">
            <label>渠道类型</label>
            <select v-model="editingChannel.type" class="form-select">
              <option v-for="ct in channelTypes" :key="ct.id" :value="ct.id">{{ ct.label }}</option>
            </select>
          </div>
          <div class="form-field">
            <label>渠道名称 <span class="required">*</span></label>
            <input v-model="editingChannel.name" class="form-input" placeholder="如：阿里云短信主通道" required />
          </div>
          <div class="form-field">
            <label>优先级（数字越小越优先）</label>
            <input type="number" v-model="editingChannel.priority" class="form-input" min="1" max="99" />
          </div>

          <!-- 动态配置字段 -->
          <div class="config-section">
            <div class="config-section-title">渠道配置</div>
            <div
              v-for="field in activeChannelType.fields"
              :key="field.key"
              class="form-field"
            >
              <label>{{ field.label }}</label>
              <input
                v-if="!field.options"
                v-model="editingChannel.config[field.key]"
                class="form-input"
                :placeholder="field.placeholder"
                type="password"
              />
              <select v-else v-model="editingChannel.config[field.key]" class="form-select">
                <option v-for="opt in field.options" :key="opt" :value="opt">{{ opt }}</option>
              </select>
            </div>
          </div>

          <div class="form-field">
            <label>降级至（主通道失败后自动切换）</label>
            <select v-model="editingChannel.fallbackTo" class="form-select">
              <option :value="null">不降级</option>
              <option
                v-for="ch in channels.filter(c => c.id !== editingChannel.id && c.enabled)"
                :key="ch.id"
                :value="ch.id"
              >{{ ch.name }}</option>
            </select>
          </div>

          <div class="form-field">
            <label>启用状态</label>
            <div style="display:flex;gap:12px;align-items:center;">
              <label style="display:flex;align-items:center;gap:6px;font-size:13px;cursor:pointer;">
                <input type="checkbox" v-model="editingChannel.enabled" />
                启用此渠道
              </label>
            </div>
          </div>
        </div>

        <div class="drawer-footer">
          <button class="btn-cancel" @click="editDrawer = false">取消</button>
          <button class="btn-primary" @click="saveChannel" :disabled="saving || !editingChannel.name.trim()">
            {{ saving ? "保存中…" : "保存" }}
          </button>
        </div>
      </div>
    </div>

    <!-- 确认对话框 -->
    <ConfirmDialog ref="confirmDialogRef" />
  </div>
</template>

<style scoped>
.channels-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--c-brand-mist);
}

/* ── 头部 ── */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 20px 24px 14px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-brand-soft);
  gap: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--c-scrim);
  margin: 0 0 4px;
}

.page-desc {
  font-size: 13px;
  color: var(--c-text-3);
  margin: 0;
}

.header-actions { display: flex; gap: 8px; flex-shrink: 0; }

/* ── 按钮 ── */
.btn-primary {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: var(--c-brand-deeper);
  color: var(--c-on-brand);
  border: none;
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-primary:hover:not(:disabled) { background: var(--sys-blue-deep); }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }

.btn-secondary {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: var(--c-surface);
  color: var(--c-ink-1);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-secondary:hover { background: var(--c-brand-mist); }

/* ── 渠道标签 ── */
.channel-tabs {
  display: flex;
  gap: 0;
  padding: 0 24px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-brand-soft);
  overflow-x: auto;
}

.channel-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 16px;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-3);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s;
}

.channel-tab:hover { color: var(--c-brand-deeper); }
.channel-tab.active { font-weight: 700; }

.tab-count {
  background: var(--c-brand-mist);
  color: var(--c-text-3);
  font-size: 11px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: var(--r-md);
}

/* ── 工具栏 ── */
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 24px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-brand-soft);
  flex-wrap: wrap;
}

.search-wrap {
  position: relative;
  min-width: 200px;
}

.search-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--c-text-3);
}

.search-input {
  width: 100%;
  padding: 7px 10px 7px 32px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
}

.search-input:focus { border-color: var(--c-brand-hover); }

.channel-summary { display: flex; gap: 8px; flex-wrap: wrap; }

.summary-chip {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: 1px solid;
  border-radius: var(--r-xl);
  font-size: 12px;
  font-weight: 500;
}

/* ── 主体 ── */
.page-body { flex: 1; overflow-y: auto; padding: 16px 24px; }

/* ── 渠道卡片 ── */
.channels-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 14px;
  margin-bottom: 24px;
}

.channel-card {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  padding: 16px;
  transition: box-shadow 0.15s;
}

.channel-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.channel-card.disabled { opacity: 0.6; }

.card-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}

.card-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--r-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.card-info { flex: 1; }

.card-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--c-scrim);
  margin-bottom: 5px;
}

.card-meta { display: flex; align-items: center; gap: 8px; }

.health-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 7px;
  border-radius: var(--r-md);
}

.priority-badge {
  font-size: 11px;
  color: var(--c-text-3);
}

/* Toggle */
.toggle-btn {
  width: 40px;
  height: 22px;
  border-radius: var(--r-md);
  background: var(--c-brand-soft);
  border: none;
  cursor: pointer;
  position: relative;
  transition: background 0.2s;
  flex-shrink: 0;
}

.toggle-btn.on { background: var(--c-success); }

.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--c-surface);
  transition: transform 0.2s;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}

.toggle-btn.on .toggle-thumb { transform: translateX(18px); }

/* 配置项 */
.card-configs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  margin-bottom: 12px;
}

.config-item {
  display: flex;
  flex-direction: column;
  gap: 1px;
  background: var(--c-brand-mist);
  padding: 5px 8px;
  border-radius: var(--r-sm);
}

.config-key {
  font-size: 10px;
  color: var(--c-text-3);
  font-weight: 600;
  text-transform: uppercase;
}

.config-val {
  font-size: 12px;
  color: var(--c-ink-1);
  font-family: monospace;
}

/* 统计 */
.card-stats {
  display: flex;
  gap: 0;
  border: 1px solid var(--c-brand-mist);
  border-radius: var(--r-sm);
  overflow: hidden;
  margin-bottom: 12px;
}

.stat-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 4px;
  border-right: 1px solid var(--c-brand-mist);
}

.stat-item:last-child { border-right: none; }

.stat-label { font-size: 11px; color: var(--c-text-3); margin-bottom: 2px; }
.stat-val { font-size: 14px; font-weight: 700; color: var(--c-scrim); }

/* 操作按钮 */
.card-actions {
  display: flex;
  gap: 6px;
}

.btn-test, .btn-edit {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 6px 10px;
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 600;
  border: 1px solid var(--c-brand-soft);
  background: var(--c-surface);
  color: var(--c-ink-1);
  cursor: pointer;
  transition: all 0.15s;
}

.btn-test:hover:not(:disabled) { background: var(--sys-blue-soft); border-color: var(--c-brand-hover); color: var(--c-brand-deeper); }
.btn-edit:hover { background: var(--c-brand-mist); }
.btn-delete {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  border: 1px solid var(--c-danger-bg);
  background: none;
  color: var(--c-danger);
  cursor: pointer;
  transition: all 0.15s;
}

.btn-delete:hover { background: var(--c-danger-bg); }

/* ── 空状态 ── */
.empty-state {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 48px;
  color: var(--c-text-3);
  font-size: 14px;
}

/* ── 发送日志 ── */
.logs-section {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  padding: 16px;
}

.logs-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 700;
  color: var(--c-ink-1);
  margin: 0 0 12px;
}

.logs-hint {
  margin-left: auto;
  font-size: 12px;
  font-weight: 400;
  color: var(--c-text-3);
}

.logs-table-wrap { overflow-x: auto; }

.logs-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.logs-table th {
  background: var(--c-brand-mist);
  padding: 8px 12px;
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-3);
  border-bottom: 1px solid var(--c-brand-soft);
}

.logs-table td {
  padding: 8px 12px;
  color: var(--c-ink-1);
  border-bottom: 1px solid var(--c-brand-mist);
}

.log-time { color: var(--c-text-3); font-family: monospace; font-size: 12px; }

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 7px;
  border-radius: var(--r-md);
  font-size: 12px;
  font-weight: 600;
}

.log-error { color: var(--c-danger); font-size: 12px; }

/* ── 抽屉 ── */
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: var(--c-overlay);
  z-index: 100;
  display: flex;
  justify-content: flex-end;
}

.drawer {
  width: 440px;
  max-width: 100%;
  background: var(--c-surface);
  height: 100%;
  display: flex;
  flex-direction: column;
  box-shadow: -4px 0 20px rgba(0,0,0,0.1);
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--c-brand-soft);
}

.drawer-header h3 {
  font-size: 16px;
  font-weight: 700;
  color: var(--c-scrim);
  margin: 0;
}

.btn-close {
  width: 32px;
  height: 32px;
  border: none;
  background: none;
  color: var(--c-text-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  transition: all 0.15s;
}

.btn-close:hover { background: var(--c-brand-mist); color: var(--c-ink-1); }

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.config-section {
  background: var(--c-brand-mist);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  padding: 14px;
}

.config-section-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--c-ink-1);
  margin-bottom: 10px;
}

.form-field { display: flex; flex-direction: column; gap: 5px; }

.form-field label {
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-3);
}

.required { color: var(--c-danger); }

.form-input, .form-select {
  padding: 8px 10px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  font-size: 13px;
  color: var(--c-ink-1);
  background: var(--c-surface);
  outline: none;
  transition: border-color 0.15s;
  font-family: inherit;
}

.form-input:focus, .form-select:focus { border-color: var(--c-brand-hover); }

.drawer-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--c-brand-soft);
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.btn-cancel {
  padding: 8px 16px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text-3);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-cancel:hover { background: var(--c-brand-mist); }

/* 动画 */
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.spinning { animation: spin 1s linear infinite; }
</style>
