<script setup>
/**
 * 渠道矩阵配置组件
 * 灾种 × 级别 × 渠道的配置界面
 */
import { computed, onMounted, reactive, ref, watch } from "vue"
import {
  Bell,
  Check,
  Loader2,
  Mail,
  MessageSquare,
  Monitor,
  Plus,
  RefreshCw,
  Save,
  Smartphone,
  TestTube2,
  Volume2,
  Webhook,
  X,
} from "lucide-vue-next"

const props = defineProps({
  disasterType: { type: String, default: "" },
  level: { type: String, default: "" },
})

const emit = defineEmits(["save", "test", "notify"])

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)

// 渠道元数据
const channelMeta = {
  SMS: { label: "短信", icon: MessageSquare, color: "#6f9e7f", priority: 1 },
  APP: { label: "APP 推送", icon: Smartphone, color: "#6f9e7f", priority: 2 },
  SCREEN: { label: "大屏", icon: Monitor, color: "#87b096", priority: 3 },
  SOUND_LIGHT: { label: "声光", icon: Volume2, color: "#cbaa76", priority: 4 },
  EMAIL: { label: "邮件", icon: Mail, color: "#805ad5", priority: 5 },
  WEBHOOK: { label: "Webhook", icon: Webhook, color: "#718096", priority: 6 },
}

const channelOptions = computed(() =>
  Object.entries(channelMeta)
    .sort((a, b) => a[1].priority - b[1].priority)
    .map(([key, meta]) => ({ value: key, ...meta }))
)

// 当前配置
const config = reactive({
  id: null,
  disasterType: props.disasterType,
  level: props.level,
  channels: [],
  templateMapping: {},
  enabled: true,
})

// 可用模板列表（实际应从 API 加载）
const templateOptions = {
  SMS: [
    { value: "TPL_LANDSLIDE_ORANGE", label: "滑坡橙色预警模板" },
    { value: "TPL_LANDSLIDE_RED", label: "滑坡红色预警模板" },
    { value: "TPL_FLOOD", label: "洪水预警模板" },
    { value: "TPL_EARTHQUAKE", label: "地震预警模板" },
    { value: "TPL_GENERAL", label: "通用预警模板" },
  ],
  APP: [
    { value: "TPL_APP_GENERAL", label: "通用推送模板" },
    { value: "TPL_APP_URGENT", label: "紧急推送模板" },
  ],
  SCREEN: [
    { value: "TPL_SCREEN_SCROLL", label: "大屏滚动通知" },
    { value: "TPL_SCREEN_ALERT", label: "大屏告警通知" },
  ],
  SOUND_LIGHT: [
    { value: "TPL_SOUND_RED", label: "红色声光指令" },
    { value: "TPL_SOUND_ORANGE", label: "橙色声光指令" },
    { value: "TPL_SOUND_YELLOW", label: "黄色声光指令" },
  ],
  EMAIL: [
    { value: "TPL_EMAIL_OFFICIAL", label: "正式邮件模板" },
    { value: "TPL_EMAIL_SUMMARY", label: "摘要邮件模板" },
  ],
  WEBHOOK: [
    { value: "TPL_WEBHOOK_DEFAULT", label: "默认 Webhook" },
  ],
}

// 加载配置
async function load() {
  if (!props.disasterType || !props.level) return
  loading.value = true
  try {
    const data = await getChannelConfigsApi(props.disasterType, props.level)
    if (data && data.length > 0) {
      const item = data[0]
      config.id = item.id
      config.templateMapping = item.templateMapping || {}
      config.enabled = item.enabled
      config.channels = item.channels?.map(ch => ({
        channel: ch.channel,
        enabled: ch.enabled,
        priority: ch.priority,
        templateId: ch.templateId || "",
      })) || []
    } else {
      config.id = null
      config.channels = channelOptions.value.map(ch => ({
        channel: ch.value,
        enabled: true,
        priority: ch.priority,
        templateId: "",
      }))
      config.templateMapping = {}
      config.enabled = true
    }
  } catch (e) {
    emit("notify", "加载配置失败: " + e.message)
    // 使用默认配置
    config.channels = channelOptions.value.map(ch => ({
      channel: ch.value,
      enabled: ["SMS", "APP", "SCREEN"].includes(ch.value),
      priority: ch.priority,
      templateId: "",
    }))
  } finally {
    loading.value = false
  }
}

// 保存配置
async function save() {
  saving.value = true
  try {
    const payload = {
      id: config.id,
      disasterType: props.disasterType,
      level: props.level,
      enabled: config.enabled,
      channels: config.channels.map(ch => ({
        channel: ch.channel,
        enabled: ch.enabled,
        priority: ch.priority,
        templateId: ch.templateId || null,
      })),
      templateMapping: config.templateMapping,
    }
    await updateConfigApi(payload)
    emit("notify", "配置已保存")
    emit("save", payload)
  } catch (e) {
    emit("notify", "保存失败: " + e.message)
  } finally {
    saving.value = false
  }
}

// 测试发送
async function test() {
  testing.value = true
  try {
    // 测试短信渠道
    const request = {
      channel: "SMS",
      target: "13800138000",
      title: "【测试】渠道配置测试",
      content: "这是一条测试消息，用于验证渠道配置是否正确。",
      variables: { level: props.level, disasterType: props.disasterType },
    }
    await testSendApi(request)
    emit("notify", "测试发送成功，请检查是否收到短信")
  } catch (e) {
    emit("notify", "测试发送失败: " + e.message)
  } finally {
    testing.value = false
  }
}

// 切换渠道启用状态
function toggleChannel(channel) {
  const item = config.channels.find(c => c.channel === channel)
  if (item) {
    item.enabled = !item.enabled
  }
}

// 获取渠道配置
async function getChannelConfigsApi(disasterType, level) {
  const { getChannelConfigs } = await import("../../services/_architecture/domain/notificationService.js")
  return getChannelConfigs(disasterType, level)
}

// 更新配置
async function updateConfigApi(config) {
  const { updateChannelConfig } = await import("../../services/_architecture/domain/notificationService.js")
  return updateChannelConfig(config)
}

// 测试发送
async function testSendApi(request) {
  const { testSend } = await import("../../services/_architecture/domain/notificationService.js")
  return testSend(request)
}

watch(() => [props.disasterType, props.level], () => load(), { immediate: true })
</script>

<template>
  <div class="channel-matrix">
    <header class="matrix-header">
      <div class="header-info">
        <Bell :size="16" />
        <span>渠道映射配置</span>
        <span class="config-badge" v-if="props.disasterType">
          {{ props.disasterType }} · {{ props.level }}
        </span>
      </div>
      <div class="header-actions">
        <button class="ghost-btn" @click="load" :disabled="loading">
          <RefreshCw :size="14" :class="{ spin: loading }" />
          刷新
        </button>
        <button class="ghost-btn" @click="test" :disabled="testing">
          <Loader2 v-if="testing" :size="14" class="spin" />
          <TestTube2 v-else :size="14" />
          测试发送
        </button>
        <button class="primary-btn" @click="save" :disabled="saving">
          <Loader2 v-if="saving" :size="14" class="spin" />
          <Save v-else :size="14" />
          保存配置
        </button>
      </div>
    </header>

    <div v-if="loading" class="loading-state">
      <Loader2 :size="24" class="spin" />
      <span>加载配置中...</span>
    </div>

    <div v-else class="matrix-body">
      <div class="channel-list">
        <div
          v-for="ch in channelOptions"
          :key="ch.value"
          class="channel-item"
          :class="{ disabled: !isChannelEnabled(ch.value) }"
        >
          <div class="channel-toggle">
            <label class="switch">
              <input
                type="checkbox"
                :checked="isChannelEnabled(ch.value)"
                @change="toggleChannel(ch.value)"
              />
              <span class="slider"></span>
            </label>
          </div>

          <div class="channel-icon" :style="{ background: ch.color + '22', color: ch.color }">
            <component :is="ch.icon" :size="18" />
          </div>

          <div class="channel-info">
            <strong>{{ ch.label }}</strong>
            <span class="priority">优先级 {{ ch.priority }}</span>
          </div>

          <div class="channel-template">
            <select
              v-if="isChannelEnabled(ch.value)"
              :value="getTemplateId(ch.value)"
              @change="setTemplateId(ch.value, $event.target.value)"
            >
              <option value="">选择模板...</option>
              <option
                v-for="tpl in (templateOptions[ch.value] || [])"
                :key="tpl.value"
                :value="tpl.value"
              >
                {{ tpl.label }}
              </option>
            </select>
            <span v-else class="template-disabled">—</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
function isChannelEnabled(channel) {
  const item = this.config.channels.find(c => c.channel === channel)
  return item?.enabled ?? false
}
function getTemplateId(channel) {
  const item = this.config.channels.find(c => c.channel === channel)
  return item?.templateId || ""
}
function setTemplateId(channel, templateId) {
  const item = this.config.channels.find(c => c.channel === channel)
  if (item) {
    item.templateId = templateId
    this.config.templateMapping[channel] = templateId
  }
}
</script>

<style scoped>
.channel-matrix {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.matrix-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--c-brand-soft);
  background: var(--c-surface-2);
}

.header-info {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--c-text);
  font-weight: 600;
}

.config-badge {
  padding: 3px 10px;
  background: var(--c-brand-mist);
  color: var(--c-brand);
  border-radius: var(--r-lg);
  font-size: 12px;
  font-weight: 500;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px;
  color: var(--c-text-2);
}

.channel-list {
  padding: 12px;
}

.channel-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 14px;
  border-radius: var(--r-sm);
  transition: background 0.2s;
}

.channel-item:hover {
  background: var(--c-surface-2);
}

.channel-item.disabled {
  opacity: 0.6;
}

.channel-toggle {
  flex-shrink: 0;
}

.channel-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: var(--r-md);
  flex-shrink: 0;
}

.channel-info {
  flex: 1;
  min-width: 0;
}

.channel-info strong {
  display: block;
  color: var(--c-text);
  font-size: 14px;
}

.priority {
  font-size: 11px;
  color: var(--c-text-2);
}

.channel-template {
  flex-shrink: 0;
  width: 180px;
}

.channel-template select {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface-2);
  font-size: 13px;
}

.template-disabled {
  color: var(--c-text-2);
}

.switch {
  position: relative;
  display: inline-block;
  width: 36px;
  height: 20px;
  cursor: pointer;
}

.switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.switch .slider {
  position: absolute;
  inset: 0;
  background: var(--c-separator);
  border-radius: var(--r-md);
  transition: background 0.2s;
}

.switch .slider::before {
  content: "";
  position: absolute;
  left: 2px;
  top: 2px;
  width: 16px;
  height: 16px;
  background: var(--c-surface);
  border-radius: 50%;
  transition: transform 0.2s;
}

.switch input:checked + .slider {
  background: var(--c-brand);
}

.switch input:checked + .slider::before {
  transform: translateX(16px);
}

.primary-btn {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 8px 14px;
  border: 0;
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-radius: var(--r-sm);
  font-size: 13px;
  cursor: pointer;
}

.primary-btn:disabled {
  opacity: 0.5;
}

.ghost-btn {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 7px 12px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface);
  border-radius: var(--r-sm);
  color: var(--c-text);
  font-size: 12px;
  cursor: pointer;
}

.ghost-btn:hover {
  background: var(--c-bg);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
