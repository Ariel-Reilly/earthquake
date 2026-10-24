<script setup>
/**
 * 预警创建弹窗组件
 * 
 * 从 WarningCreatePage.vue 抽取的可复用弹窗。
 * 包含 4 步向导：基本信息 -> 发布范围 -> 渠道与内容 -> 确认发布。
 */
import { computed, onMounted, reactive, ref } from "vue"
import {
  AlertTriangle,
  Bell,
  Loader2,
  MapPin,
  MessageSquare,
  Send,
  Smartphone,
  Volume2,
  X,
} from "lucide-vue-next"
import { dashboardApi } from "../services/dashboardApi"
import { getAuthUser, hasPermission } from "../stores/auth"
import {
  HAZARD_TYPES,
  hazardTypeLabel as hazardTypeLabelOf,
  normalizeHazardType,
} from "../utils/hazardTypes"

const emit = defineEmits(["success", "close"])

const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }

const form = reactive({
  title: "",
  level: "orange",
  hazardCode: "",
  hazardName: "",
  hazardType: "landslide",
  region: "",
  lon: null,
  lat: null,
  bufferKm: 5,
  templateId: "tpl-landslide-orange",
  channels: { sms: true, app: true, station: true, sound: false, call: false },
  validUntil: "",
  content: "",
  source: "manual",
})

const step = ref(1)
const saving = ref(false)
const submitted = ref(null)

const templates = [
  {
    id: "tpl-landslide-orange",
    disasterType: "landslide",
    level: "orange",
    title: "【橙色预警】{region}发生滑坡险情",
    content: "【地脉智巡·橙色预警】\n险情：{hazardName}\n时间：{time}\n影响范围：以灾害点为中心 {bufferKm} 公里缓冲区\n请相关人员立即疏散至安全区域，禁止返回。\n【应急指挥中心】",
  },
  {
    id: "tpl-collapse-red",
    disasterType: "collapse",
    level: "red",
    title: "【红色预警】{region}发生崩塌险情",
    content: "【地脉智巡·红色预警·最高级】\n险情：{hazardName}\n时间：{time}\n影响范围：以灾害点为中心 {bufferKm} 公里缓冲区\n请即刻启动应急预案，组织周边群众撤离。\n【应急指挥中心】",
  },
  {
    id: "tpl-debris-yellow",
    disasterType: "debris_flow",
    level: "yellow",
    title: "【黄色预警】{region}可能发生泥石流",
    content: "【地脉智巡·黄色预警】\n区域：{hazardName}\n时间：{time}\n请密切监视降雨情况，避免进入沟谷。\n【应急指挥中心】",
  },
  {
    id: "tpl-custom",
    disasterType: "custom",
    level: "blue",
    title: "",
    content: "",
  },
]

const hazardOptions = HAZARD_TYPES

const levelMeta = {
  red:    { label: "红色", color: "#c7553b", desc: "特别重大 / 立即撤离", weight: 4 },
  orange: { label: "橙色", color: "#c4a86e", desc: "重大 / 紧急处置",     weight: 3 },
  yellow: { label: "黄色", color: "#e6b34a", desc: "较大 / 注意防范",     weight: 2 },
  blue:   { label: "蓝色", color: "#5d95c1", desc: "一般 / 关注",          weight: 1 },
}

const channelMeta = [
  { id: "sms",    label: "短信",   icon: MessageSquare, desc: "高风险预警实时短信提醒", recommend: ["red", "orange"] },
  { id: "app",    label: "APP 推送", icon: Smartphone, desc: "地脉智巡 APP 推送", recommend: ["red", "orange", "yellow"] },
  { id: "station", label: "站内消息", icon: Bell, desc: "站内消息中心推送", recommend: ["red", "orange", "yellow", "blue"] },
  { id: "sound",  label: "声光",   icon: Volume2, desc: "声光报警器触发",  recommend: ["red"] },
  { id: "call",   label: "电话",   icon: Smartphone, desc: "红色预警自动电话", recommend: ["red"] },
]

const filteredTemplates = computed(() => {
  const matched = templates.filter((t) => t.disasterType === form.hazardType)
  return matched.length > 0
    ? [...matched, ...templates.filter((t) => t.id === "tpl-custom")]
    : templates
})

function applyTemplate(tplId) {
  const tpl = templates.find((t) => t.id === tplId)
  if (!tpl) return
  form.templateId = tplId
  if (tplId === "tpl-custom") return
  form.title = tpl.title
  form.level = tpl.level
  form.content = tpl.content
}

function renderContent(tpl) {
  const time = new Date().toLocaleString("zh-CN", { hour12: false })
  return tpl
    .replace("{region}", form.region || "____")
    .replace("{hazardName}", form.hazardName || "____")
    .replace("{time}", time)
    .replace("{bufferKm}", String(form.bufferKm))
}

const previewContent = computed(() => renderContent(form.content))
const previewTitle = computed(() => renderContent(form.title))
const hazardTypeLabel = computed(() => hazardTypeLabelOf(form.hazardType))

function selectHazard(h) {
  form.hazardCode = h.hazardCode ?? ""
  form.hazardName = h.name ?? ""
  form.hazardType = normalizeHazardType(h.type ?? "landslide")
  form.lon = h.lon ?? null
  form.lat = h.lat ?? null
  form.region = (form.region || h.region) ?? ""
  const matched = templates.find(
    (t) => t.disasterType === form.hazardType && t.level === form.level,
  )
  if (matched) applyTemplate(matched.id)
}

async function bootstrap() {
  if (!form.validUntil) {
    const d = new Date(Date.now() + 24 * 60 * 60 * 1000)
    form.validUntil = d.toISOString().slice(0, 16)
  }
}

const recommendedChannels = computed(() =>
  channelMeta.filter((c) => c.recommend.includes(form.level)).map((c) => c.id),
)

const canNext = computed(() => {
  if (step.value === 1) return form.title.trim() && form.hazardCode
  if (step.value === 2) return form.region.trim() && form.lon != null && form.lat != null
  if (step.value === 3) return Object.values(form.channels).some(Boolean) && form.content.trim()
  return true
})

function nextStep() {
  if (!canNext.value) return
  step.value = Math.min(step.value + 1, 4)
}
function prevStep() { step.value = Math.max(step.value - 1, 1) }

async function submit() {
  saving.value = true
  try {
    const result = await dashboardApi.createWarning({
      title: form.title,
      level: form.level,
      hazardCode: form.hazardCode,
      hazardName: form.hazardName,
      hazardType: form.hazardType,
      region: form.region,
      lon: form.lon,
      lat: form.lat,
      bufferKm: form.bufferKm,
      channels: Object.entries(form.channels).filter(([, v]) => v).map(([k]) => k),
      content: form.content,
      validUntil: form.validUntil,
      operator: operator.name,
    })
    submitted.value = result ?? { id: `WG-${Date.now()}`, title: form.title, level: form.level }
    // 成功后发送事件通知父组件
    emit("success", submitted.value)
  } catch (e) {
    // 错误处理由父组件通过 notify 事件处理
    throw e
  } finally {
    saving.value = false
  }
}

const has = (perm) => hasPermission(operator.role, perm)

onMounted(() => bootstrap())

// 重置表单方法
function reset() {
  step.value = 1
  submitted.value = null
  form.title = ""
  form.level = "orange"
  form.hazardCode = ""
  form.hazardName = ""
  form.hazardType = "landslide"
  form.region = ""
  form.lon = null
  form.lat = null
  form.bufferKm = 5
  form.templateId = "tpl-landslide-orange"
  form.channels = { sms: true, app: true, station: true, sound: false, call: false }
  form.validUntil = ""
  form.content = ""
  form.source = "manual"
}

// 暴露方法给父组件
defineExpose({ reset })
</script>

<template>
  <div class="warning-create-modal-overlay">
    <!-- 背景 -->
    <div class="modal-backdrop" @click="emit('close')" />
    
    <!-- 弹窗 -->
    <div class="modal-window">
      <header class="modal-header">
        <div>
          <h2>发布预警</h2>
          <p>4 步向导：基本信息 → 发布范围 → 渠道与内容 → 确认发布</p>
        </div>
        <button class="close-btn" @click="emit('close')"><X :size="18" /></button>
      </header>

      <div class="step-indicator">
        <span v-for="(label, i) in ['基本信息', '发布范围', '渠道与内容', '确认发布']" :key="i" :class="{ active: step === i + 1, done: step > i + 1 }">
          <code>{{ i + 1 }}</code>{{ label }}
        </span>
      </div>

      <div class="modal-body">
        <section v-if="step === 1" class="step-pane">
          <label>预警标题
            <input v-model="form.title" placeholder="如：龙安镇滑坡橙色预警" />
          </label>
          <div class="row-2">
            <label>预警级别
              <div class="level-pick">
                <button v-for="lvl in Object.entries(levelMeta)" :key="lvl[0]"
                  :class="{ active: form.level === lvl[0] }"
                  :style="{ borderColor: lvl[1].color, background: form.level === lvl[0] ? lvl[1].color + '15' : '' }"
                  @click="form.level = lvl[0]"
                >
                  <strong :style="{ color: lvl[1].color }">{{ lvl[1].label }}</strong>
                  <small>{{ lvl[1].desc }}</small>
                </button>
              </div>
            </label>
          </div>
          <div class="row-2">
            <label>灾种
              <select v-model="form.hazardType" @change="form.templateId = 'tpl-custom'">
                <option v-for="opt in hazardOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
            </label>
            <label>关联灾害点编号
              <input v-model="form.hazardCode" placeholder="如 HZ-001" />
            </label>
          </div>
          <label>灾害点名称
            <input v-model="form.hazardName" placeholder="如 青龙坡滑坡隐患点" />
          </label>
          <label>预警生效时间至
            <input v-model="form.validUntil" type="datetime-local" />
          </label>
        </section>

        <section v-if="step === 2" class="step-pane">
          <label>影响行政区 / 区域
            <input v-model="form.region" placeholder="如 龙安镇" />
          </label>
          <div class="row-2">
            <label>中心经度
              <input v-model.number="form.lon" type="number" step="0.0001" placeholder="107.xxxx" />
            </label>
            <label>中心纬度
              <input v-model.number="form.lat" type="number" step="0.0001" placeholder="30.xxxx" />
            </label>
          </div>
          <label>缓冲区半径（公里）：{{ form.bufferKm }} km
            <input v-model.number="form.bufferKm" type="range" min="1" max="50" step="1" />
          </label>
          <div class="map-stub">
            <MapPin :size="22" />
            <p>地图预览：中心点 ({{ form.lon ?? "—" }}, {{ form.lat ?? "—" }})，覆盖半径 {{ form.bufferKm }} km</p>
          </div>
        </section>

        <section v-if="step === 3" class="step-pane">
          <label>选择模板
            <div class="tpl-list">
              <button v-for="tpl in filteredTemplates" :key="tpl.id"
                :class="{ active: form.templateId === tpl.id }"
                @click="applyTemplate(tpl.id)"
              >
                <strong>{{ hazardTypeLabelOf(tpl.disasterType) }} · {{ levelMeta[tpl.level]?.label || '自定义' }}</strong>
                <small>{{ tpl.title || '自定义文案' }}</small>
              </button>
            </div>
          </label>
          <label>通知渠道
            <div class="channels">
              <label v-for="c in channelMeta" :key="c.id" class="channel-pick" :class="{ active: form.channels[c.id], recommended: recommendedChannels.includes(c.id) && !form.channels[c.id] }">
                <input type="checkbox" v-model="form.channels[c.id]" />
                <component :is="c.icon" :size="18" />
                <strong>{{ c.label }}</strong>
                <small>{{ c.desc }}</small>
                <span v-if="recommendedChannels.includes(c.id)" class="rec-tag">推荐</span>
              </label>
            </div>
          </label>
          <label>预警正文
            <textarea v-model="form.content" rows="6" placeholder="支持变量 {region} {hazardName} {time} {bufferKm}" />
          </label>
        </section>

        <section v-if="step === 4" class="step-pane">
          <h3>发布前确认</h3>
          <ul class="summary">
            <li><strong>标题：</strong>{{ form.title }}</li>
            <li><strong>级别：</strong><span :style="{ color: levelMeta[form.level].color }">{{ levelMeta[form.level].label }} · {{ levelMeta[form.level].desc }}</span></li>
            <li><strong>灾种 / 灾害点：</strong>{{ hazardTypeLabel }} · {{ form.hazardName || '—' }}</li>
            <li><strong>影响区域：</strong>{{ form.region }}（{{ form.bufferKm }} km 缓冲区）</li>
            <li><strong>渠道：</strong>{{ Object.entries(form.channels).filter(([, v]) => v).map(([k]) => channelMeta.find((c) => c.id === k)?.label).join('、') }}</li>
            <li><strong>生效至：</strong>{{ form.validUntil }}</li>
          </ul>
          <div class="preview">
            <strong>正文预览：</strong>
            <pre>{{ previewContent }}</pre>
          </div>
        </section>
      </div>

      <footer class="modal-footer">
        <button v-if="step > 1" class="ghost-btn" @click="prevStep">上一步</button>
        <button v-if="step < 4" class="primary-btn" :disabled="!canNext" @click="nextStep">
          下一步
        </button>
        <button v-else class="primary-btn" :disabled="saving || !has('warnings:create')" @click="submit">
          <Loader2 v-if="saving" :size="14" class="spin" />
          <Send v-else :size="14" />{{ saving ? '提交中…' : '提交发布' }}
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.warning-create-modal-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.modal-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
}

.modal-window {
  position: relative;
  z-index: 101;
  background: var(--c-surface);
  border-radius: var(--r-lg);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  width: 90vw;
  max-width: 720px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 20px 24px;
  border-bottom: 1px solid var(--c-separator);
  flex-shrink: 0;
}

.modal-header h2 {
  margin: 0 0 4px;
  font-size: 18px;
  color: var(--c-scrim);
  font-weight: 700;
}

.modal-header p {
  margin: 0;
  font-size: 12px;
  color: var(--c-text-2);
}

.close-btn {
  padding: 4px;
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--c-text-2);
  display: flex;
  align-items: center;
  justify-content: center;
}

.close-btn:hover {
  color: var(--c-scrim);
}

.step-indicator {
  display: flex;
  gap: 8px;
  padding: 12px 24px;
  background: var(--c-bg);
  border-bottom: 1px solid var(--c-separator);
  flex-shrink: 0;
  overflow-x: auto;
}

.step-indicator span {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 6px 10px;
  background: var(--c-surface);
  border-radius: var(--r-lg);
  font-size: 12px;
  color: var(--c-text-2);
  white-space: nowrap;
}

.step-indicator span code {
  width: 18px;
  height: 18px;
  background: var(--c-surface-2);
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 10px;
  color: var(--c-brand-deep);
}

.step-indicator span.active {
  background: var(--c-brand-light);
  color: var(--c-brand);
  font-weight: 600;
}

.step-indicator span.active code {
  background: var(--c-brand);
  color: var(--c-on-brand);
}

.step-indicator span.done code {
  background: var(--c-brand-deep);
  color: var(--c-on-brand);
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.step-pane label {
  display: block;
  margin-bottom: 18px;
  color: var(--c-brand-deep);
  font-size: 12px;
  font-weight: 600;
}

.step-pane input,
.step-pane textarea,
.step-pane select {
  display: block;
  width: 100%;
  margin-top: 6px;
  padding: 9px 12px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface-2);
  font-size: 13px;
}

.step-pane input[type="range"] {
  padding: 0;
  background: transparent;
}

.step-pane textarea {
  font-family: monospace;
}

.row-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.level-pick {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-top: 6px;
}

.level-pick button {
  padding: 10px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface-2);
  cursor: pointer;
  text-align: left;
}

.level-pick button.active {
  border-width: 2px;
}

.level-pick button strong {
  display: block;
  font-size: 14px;
}

.level-pick button small {
  display: block;
  color: var(--c-text-2);
  font-size: 10px;
  margin-top: 2px;
}

.map-stub {
  padding: 22px;
  background: var(--c-bg);
  border-radius: var(--r-md);
  text-align: center;
  color: var(--c-brand-deep);
}

.map-stub p {
  margin: 8px 0 0;
  font-size: 12px;
}

.tpl-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 6px;
}

.tpl-list button {
  padding: 10px 12px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface-2);
  border-radius: var(--r-sm);
  cursor: pointer;
  text-align: left;
}

.tpl-list button.active {
  border-color: var(--c-brand);
  background: color-mix(in srgb, var(--c-brand) 10%, var(--c-surface-2));
}

.tpl-list button strong {
  display: block;
  font-size: 13px;
  color: var(--c-scrim);
}

.tpl-list button small {
  display: block;
  color: var(--c-text-2);
  font-size: 11px;
  margin-top: 2px;
}

.channels {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
  margin-top: 6px;
}

.channel-pick {
  position: relative;
  padding: 10px 12px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface-2);
  border-radius: var(--r-sm);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.channel-pick input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.channel-pick.active {
  border-color: var(--c-brand);
  background: color-mix(in srgb, var(--c-brand) 10%, var(--c-surface-2));
}

.channel-pick strong {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 13px;
  color: var(--c-scrim);
}

.channel-pick small {
  font-size: 11px;
  color: var(--c-text-2);
}

.rec-tag {
  position: absolute;
  top: 2px;
  right: 2px;
  padding: 2px 5px;
  background: var(--c-warning-bg);
  color: var(--c-warning-ink);
  border-radius: var(--r-xs);
  font-size: 9px;
  font-weight: 600;
}

.summary {
  list-style: none;
  padding: 0;
  margin: 0 0 12px;
}

.summary li {
  padding: 6px 0;
  border-bottom: 1px dashed var(--c-separator);
  font-size: 13px;
  color: var(--c-text-2);
}

.summary li:last-child {
  border-bottom: none;
}

.summary strong {
  color: var(--c-scrim);
  font-weight: 600;
}

.preview {
  margin-top: 12px;
  padding: 12px;
  background: var(--c-bg);
  border-radius: var(--r-sm);
}

.preview strong {
  display: block;
  font-size: 12px;
  color: var(--c-scrim);
  margin-bottom: 6px;
}

.preview pre {
  margin: 0;
  padding: 8px;
  background: var(--c-surface-2);
  border-radius: var(--r-xs);
  font-size: 11px;
  color: var(--c-text-1);
  overflow-x: auto;
  line-height: 1.4;
}

.modal-footer {
  padding: 16px 24px;
  border-top: 1px solid var(--c-separator);
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  flex-shrink: 0;
  background: var(--c-bg);
}

.primary-btn {
  padding: 9px 16px;
  border: none;
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: background 0.2s;
}

.primary-btn:hover:not(:disabled) {
  background: var(--c-brand-deep);
}

.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ghost-btn {
  padding: 9px 16px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface);
  color: var(--c-text-1);
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition: all 0.2s;
}

.ghost-btn:hover {
  border-color: var(--c-brand);
  background: var(--c-brand-light);
}

.spin {
  animation: spin 1.2s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
