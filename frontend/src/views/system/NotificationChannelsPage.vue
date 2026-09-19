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
import { dashboardApi } from "../../services/dashboardApi";
import { hasPermission, getAuthUser } from "../../stores/auth";

const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" };
const has = (perm) => hasPermission(operator.role, perm);

const loading = ref(false);
const activeTab = ref("sms");
const searchTerm = ref("");

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
    color: "#3b82f6",
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

// ── 渠道列表（mock） ──
const channels = ref([
  {
    id: "ch-001",
    type: "sms",
    name: "阿里云短信主通道",
    enabled: true,
    priority: 1,
    health: "healthy",
    healthLabel: "正常",
    lastSend: "2 分钟前",
    todayCount: 1234,
    successRate: 99.2,
    fallbackTo: "ch-003",
    config: { endpoint: "https://dysmsapi.aliyuncs.com", signName: "【地脉智巡】" },
  },
  {
    id: "ch-002",
    type: "app",
    name: "APP 推送通道",
    enabled: true,
    priority: 2,
    health: "healthy",
    healthLabel: "正常",
    lastSend: "5 分钟前",
    todayCount: 3421,
    successRate: 98.7,
    fallbackTo: null,
    config: { channelId: "disasterguard_alert", importance: "HIGH" },
  },
  {
    id: "ch-003",
    type: "email",
    name: "邮件通知通道",
    enabled: true,
    priority: 3,
    health: "degraded",
    healthLabel: "性能下降",
    lastSend: "15 分钟前",
    todayCount: 567,
    successRate: 94.5,
    fallbackTo: null,
    config: { smtpHost: "smtp.exmail.qq.com", fromAddress: "noreply@disasterguard.cn" },
  },
  {
    id: "ch-004",
    type: "led",
    name: "指挥大厅 LED",
    enabled: false,
    priority: 5,
    health: "offline",
    healthLabel: "已停用",
    lastSend: "3 天前",
    todayCount: 0,
    successRate: 0,
    fallbackTo: null,
    config: { endpoint: "http://192.168.1.100:8080", screenId: "LED-01" },
  },
  {
    id: "ch-005",
    type: "webhook",
    name: "应急指挥系统回调",
    enabled: true,
    priority: 4,
    health: "healthy",
    healthLabel: "正常",
    lastSend: "1 小时前",
    todayCount: 89,
    successRate: 100,
    fallbackTo: null,
    config: { url: "https://emergency-api.example.com/webhook" },
  },
  {
    id: "ch-006",
    type: "sound",
    name: "现场声光报警器",
    enabled: true,
    priority: 6,
    health: "healthy",
    healthLabel: "正常",
    lastSend: "昨天",
    todayCount: 3,
    successRate: 100,
    fallbackTo: null,
    config: { deviceIp: "192.168.1.200", soundPattern: "urgent" },
  },
]);

// 过滤
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

// ── 发送日志（mock） ──
const logs = ref([
  { id: "lg-001", channel: "短信", recipient: "138****0001", template: "橙色预警通知", status: "success", sentAt: "14:35:21", errorMsg: "" },
  { id: "lg-002", channel: "APP推送", recipient: "李晨曦", template: "待确认提醒", status: "success", sentAt: "14:32:05", errorMsg: "" },
  { id: "lg-003", channel: "Webhook", recipient: "emergency-api", template: "预警发布事件", status: "success", sentAt: "14:30:00", errorMsg: "" },
  { id: "lg-004", channel: "短信", recipient: "139****5678", template: "橙色预警通知", status: "failed", sentAt: "14:29:33", errorMsg: "手机号无效" },
  { id: "lg-005", channel: "邮件", recipient: "expert@disasterguard.cn", template: "专家会签通知", status: "success", sentAt: "14:28:10", errorMsg: "" },
]);

// ── 状态 ──
const editDrawer = ref(false);
const editingChannel = ref(null);
const testing = ref({});
const saving = ref(false);

// ── 操作 ──
function openCreate() {
  editingChannel.value = {
    id: null,
    type: activeTab.value,
    name: "",
    enabled: true,
    priority: channels.value.filter((c) => c.type === activeTab.value).length + 1,
    config: {},
    fallbackTo: null,
  };
  editDrawer.value = true;
}

function openEdit(channel) {
  editingChannel.value = { ...channel, config: { ...channel.config } };
  editDrawer.value = true;
}

async function testConnection(channel) {
  testing.value[channel.id] = true;
  try {
    // 真实场景：await dashboardApi.testChannel(channel)
    await new Promise((r) => setTimeout(r, 1200));
    alert(`✓ ${channel.name} 连接测试成功`);
  } catch (e) {
    alert(`✗ 测试失败: ${e.message}`);
  } finally {
    testing.value[channel.id] = false;
  }
}

async function toggleChannel(channel) {
  channel.enabled = !channel.enabled;
  // 真实场景：await dashboardApi.updateChannel(channel)
}

async function saveChannel() {
  saving.value = true;
  try {
    await new Promise((r) => setTimeout(r, 600));
    const idx = channels.value.findIndex((c) => c.id === editingChannel.value.id);
    if (idx >= 0) {
      channels.value[idx] = { ...editingChannel.value };
    } else {
      editingChannel.value.id = `ch-${Date.now()}`;
      channels.value.push({ ...editingChannel.value, health: "healthy", healthLabel: "正常", todayCount: 0, successRate: 0 });
    }
    editDrawer.value = false;
  } finally {
    saving.value = false;
  }
}

async function deleteChannel(channel) {
  if (!confirm(`确定删除渠道 "${channel.name}"？`)) return;
  channels.value = channels.value.filter((c) => c.id !== channel.id);
}

function goToTemplates() {
  window.location.href = "/dashboard/notice-templates";
}

// ── 样式 ──
const healthStyle = (h) =>
  h === "healthy" ? { bg: "#dcfce7", color: "#166534" } :
  h === "degraded" ? { bg: "#fef9c3", color: "#854d0e" } :
  { bg: "#f1f5f9", color: "#64748b" };

const statusStyle = (s) =>
  s === "success" ? { bg: "#dcfce7", color: "#166534" } :
  { bg: "#fee2e2", color: "#991b1b" };

const channelIcon = (type) => {
  const t = channelTypes.find((c) => c.id === type);
  return t?.icon ?? Bell;
};

const channelColor = (type) => {
  const t = channelTypes.find((c) => c.id === type);
  return t?.color ?? "#64748b";
};
</script>

<template>
  <div class="channels-page">
    <!-- 顶部 -->
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
          <!-- 头部 -->
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

          <!-- 统计 -->
          <div class="card-stats">
            <div class="stat-item">
              <span class="stat-label">今日发送</span>
              <span class="stat-val">{{ ch.todayCount.toLocaleString() }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">成功率</span>
              <span class="stat-val" :style="{ color: ch.successRate >= 98 ? '#22c55e' : ch.successRate >= 90 ? '#f59e0b' : '#ef4444' }">
                {{ ch.successRate }}%
              </span>
            </div>
            <div class="stat-item">
              <span class="stat-label">上次发送</span>
              <span class="stat-val">{{ ch.lastSend }}</span>
            </div>
          </div>

          <!-- 操作 -->
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

        <!-- 空状态 -->
        <div v-if="!tabChannels.length" class="empty-state">
          <Bell :size="40" />
          <p>该类型暂无渠道配置</p>
          <button class="btn-primary" @click="openCreate">
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
                    <XCircle v-else :size="11" />
                    {{ log.status === "success" ? "成功" : "失败" }}
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
  </div>
</template>

<style scoped>
.channels-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f8fafc;
}

/* ── 头部 ── */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 20px 24px 14px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  gap: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: #1e293b;
  margin: 0 0 4px;
}

.page-desc {
  font-size: 13px;
  color: #64748b;
  margin: 0;
}

.header-actions { display: flex; gap: 8px; flex-shrink: 0; }

/* ── 按钮 ── */
.btn-primary {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: #1d4ed8;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-primary:hover:not(:disabled) { background: #1e40af; }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }

.btn-secondary {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: #fff;
  color: #374151;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-secondary:hover { background: #f8fafc; }

/* ── 渠道标签 ── */
.channel-tabs {
  display: flex;
  gap: 0;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
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
  color: #64748b;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s;
}

.channel-tab:hover { color: #1d4ed8; }
.channel-tab.active { font-weight: 700; }

.tab-count {
  background: #f1f5f9;
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: 10px;
}

/* ── 工具栏 ── */
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
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
  color: #94a3b8;
}

.search-input {
  width: 100%;
  padding: 7px 10px 7px 32px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
}

.search-input:focus { border-color: #3b82f6; }

.channel-summary { display: flex; gap: 8px; flex-wrap: wrap; }

.summary-chip {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: 1px solid;
  border-radius: 20px;
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
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
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
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.card-info { flex: 1; }

.card-name {
  font-size: 14px;
  font-weight: 700;
  color: #1e293b;
  margin-bottom: 5px;
}

.card-meta { display: flex; align-items: center; gap: 8px; }

.health-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 7px;
  border-radius: 10px;
}

.priority-badge {
  font-size: 11px;
  color: #94a3b8;
}

/* Toggle */
.toggle-btn {
  width: 40px;
  height: 22px;
  border-radius: 11px;
  background: #e2e8f0;
  border: none;
  cursor: pointer;
  position: relative;
  transition: background 0.2s;
  flex-shrink: 0;
}

.toggle-btn.on { background: #22c55e; }

.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
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
  background: #f8fafc;
  padding: 5px 8px;
  border-radius: 5px;
}

.config-key {
  font-size: 10px;
  color: #94a3b8;
  font-weight: 600;
  text-transform: uppercase;
}

.config-val {
  font-size: 12px;
  color: #374151;
  font-family: monospace;
}

/* 统计 */
.card-stats {
  display: flex;
  gap: 0;
  border: 1px solid #f1f5f9;
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 12px;
}

.stat-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 4px;
  border-right: 1px solid #f1f5f9;
}

.stat-item:last-child { border-right: none; }

.stat-label { font-size: 11px; color: #94a3b8; margin-bottom: 2px; }
.stat-val { font-size: 14px; font-weight: 700; color: #1e293b; }

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
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #374151;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-test:hover:not(:disabled) { background: #eff6ff; border-color: #3b82f6; color: #1d4ed8; }
.btn-edit:hover { background: #f8fafc; }
.btn-delete {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  border: 1px solid #fee2e2;
  background: none;
  color: #ef4444;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-delete:hover { background: #fee2e2; }

/* ── 空状态 ── */
.empty-state {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 48px;
  color: #94a3b8;
  font-size: 14px;
}

/* ── 发送日志 ── */
.logs-section {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
}

.logs-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 700;
  color: #374151;
  margin: 0 0 12px;
}

.logs-hint {
  margin-left: auto;
  font-size: 12px;
  font-weight: 400;
  color: #94a3b8;
}

.logs-table-wrap { overflow-x: auto; }

.logs-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.logs-table th {
  background: #f8fafc;
  padding: 8px 12px;
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  border-bottom: 1px solid #e2e8f0;
}

.logs-table td {
  padding: 8px 12px;
  color: #374151;
  border-bottom: 1px solid #f1f5f9;
}

.log-time { color: #94a3b8; font-family: monospace; font-size: 12px; }

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 7px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
}

.log-error { color: #ef4444; font-size: 12px; }

/* ── 抽屉 ── */
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 100;
  display: flex;
  justify-content: flex-end;
}

.drawer {
  width: 440px;
  max-width: 100%;
  background: #fff;
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
  border-bottom: 1px solid #e2e8f0;
}

.drawer-header h3 {
  font-size: 16px;
  font-weight: 700;
  color: #1e293b;
  margin: 0;
}

.btn-close {
  width: 32px;
  height: 32px;
  border: none;
  background: none;
  color: #94a3b8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  transition: all 0.15s;
}

.btn-close:hover { background: #f1f5f9; color: #374151; }

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.config-section {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px;
}

.config-section-title {
  font-size: 13px;
  font-weight: 700;
  color: #374151;
  margin-bottom: 10px;
}

.form-field { display: flex; flex-direction: column; gap: 5px; }

.form-field label {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
}

.required { color: #ef4444; }

.form-input, .form-select {
  padding: 8px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 13px;
  color: #374151;
  background: #fff;
  outline: none;
  transition: border-color 0.15s;
  font-family: inherit;
}

.form-input:focus, .form-select:focus { border-color: #3b82f6; }

.drawer-footer {
  padding: 14px 20px;
  border-top: 1px solid #e2e8f0;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.btn-cancel {
  padding: 8px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  color: #64748b;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-cancel:hover { background: #f8fafc; }

/* 动画 */
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.spinning { animation: spin 1s linear infinite; }
</style>
