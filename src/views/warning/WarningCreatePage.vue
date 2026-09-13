<script setup>
/**
 * 预警创建与编辑（独立页面 /dashboard/warnings/create）。
 *
 * 4 步向导：基本信息 -> 发布范围 -> 渠道与内容 -> 确认发布。
 */
import { computed, onMounted, reactive, ref } from "vue"
import { useRoute, useRouter } from "vue-router"
import {
  AlertTriangle,
  Bell,
  ChevronLeft,
  Loader2,
  MapPin,
  MessageSquare,
  Save,
  Send,
  Smartphone,
  Volume2,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"
import { getAuthUser, hasPermission } from "../../stores/auth"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const route = useRoute()
const router = useRouter()
const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }

const form = reactive({
  title: "",
  level: "orange",
  hazardCode: "",
  hazardName: "",
  hazardType: "滑坡",
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
    disasterType: "滑坡",
    level: "orange",
    title: "【橙色预警】{region}发生滑坡险情",
    content: "【地脉智巡·橙色预警】\n险情：{hazardName}\n时间：{time}\n影响范围：以灾害点为中心 {bufferKm} 公里缓冲区\n请相关人员立即疏散至安全区域，禁止返回。\n【应急指挥中心】",
  },
  {
    id: "tpl-collapse-red",
    disasterType: "崩塌",
    level: "red",
    title: "【红色预警】{region}发生崩塌险情",
    content: "【地脉智巡·红色预警·最高级】\n险情：{hazardName}\n时间：{time}\n影响范围：以灾害点为中心 {bufferKm} 公里缓冲区\n请即刻启动应急预案，组织周边群众撤离。\n【应急指挥中心】",
  },
  {
    id: "tpl-debris-yellow",
    disasterType: "泥石流",
    level: "yellow",
    title: "【黄色预警】{region}可能发生泥石流",
    content: "【地脉智巡·黄色预警】\n区域：{hazardName}\n时间：{time}\n请密切监视降雨情况，避免进入沟谷。\n【应急指挥中心】",
  },
  {
    id: "tpl-custom",
    disasterType: "通用",
    level: "blue",
    title: "",
    content: "",
  },
]

const levelMeta = {
  red:    { label: "红色", color: "#c7553b", desc: "特别重大 / 立即撤离", weight: 4 },
  orange: { label: "橙色", color: "#d7a546", desc: "重大 / 紧急处置",     weight: 3 },
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

const filteredTemplates = computed(() =>
  templates.filter((t) => t.disasterType === form.hazardType || t.id === "tpl-custom"),
)

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

function selectHazard(h) {
  form.hazardCode = h.hazardCode ?? ""
  form.hazardName = h.name ?? ""
  form.hazardType = h.type ?? "滑坡"
  form.lon = h.lon ?? null
  form.lat = h.lat ?? null
  form.region = (form.region || h.region) ?? ""
  const matched = templates.find(
    (t) => t.disasterType === form.hazardType && t.level === form.level,
  )
  if (matched) applyTemplate(matched.id)
  emit("notify", `已关联灾害点：${h.name}`)
}

async function bootstrap() {
  const hazardId = route.query.hazardId
  if (hazardId) {
    try {
      const hazard = await dashboardApi.getHazardDetail(hazardId)
      selectHazard(hazard)
    } catch {}
  }
  if (!form.validUntil) {
    const d = new Date(Date.now() + 24 * 60 * 60 * 1000)
    form.validUntil = d.toISOString().slice(0, 16)
  }
}

const recommendedChannels = computed(() =>
  channelMeta.filter((c) => c.recommend.includes(form.level)).map((c) => c.id),
)

const stepTitle = computed(() => ["基本信息", "发布范围", "渠道与内容", "确认发布"][step.value - 1])
const canNext = computed(() => {
  if (step.value === 1) return form.title.trim() && form.hazardType
  if (step.value === 2) return form.region.trim() && form.lon != null && form.lat != null
  if (step.value === 3) return Object.values(form.channels).some(Boolean) && form.content.trim()
  return true
})

function nextStep() {
  if (!canNext.value) return emit("notify", "请补全必填项")
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
    emit("notify", "预警创建成功，即将进入审批流程")
  } catch (e) {
    emit("notify", e.message ?? "创建失败")
  } finally {
    saving.value = false
  }
}

function viewSubmitted() {
  if (!submitted.value?.id) return
  router.push(`/dashboard/warnings/${submitted.value.id}`)
}

const has = (perm) => hasPermission(operator.role, perm)

onMounted(() => bootstrap())
</script>

<template>
  <div class="warning-create-shell">
    <header class="page-header">
      <div>
        <button class="back-btn" @click="router.back()"><ChevronLeft :size="14" />返回</button>
        <h2><AlertTriangle :size="18" />创建预警</h2>
        <p>4 步走完预警创建 —— {{ stepTitle }}</p>
      </div>
      <div class="step-indicator">
        <span v-for="(label, i) in ['基本信息', '发布范围', '渠道与内容', '确认发布']" :key="i" :class="{ active: step === i + 1, done: step > i + 1 }">
          <code>{{ i + 1 }}</code>{{ label }}
        </span>
      </div>
    </header>

    <div v-if="!submitted" class="form-card">
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
              <option v-for="t in ['滑坡','崩塌','泥石流','地面塌陷','地震','洪水','火灾']" :key="t">{{ t }}</option>
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
              <strong>{{ tpl.disasterType }} · {{ levelMeta[tpl.level]?.label || '自定义' }}</strong>
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
          <li><strong>灾种 / 灾害点：</strong>{{ form.hazardType }} · {{ form.hazardName || '—' }}</li>
          <li><strong>影响区域：</strong>{{ form.region }}（{{ form.bufferKm }} km 缓冲区）</li>
          <li><strong>渠道：</strong>{{ Object.entries(form.channels).filter(([, v]) => v).map(([k]) => channelMeta.find((c) => c.id === k)?.label).join('、') }}</li>
          <li><strong>生效至：</strong>{{ form.validUntil }}</li>
        </ul>
        <div class="preview">
          <strong>正文预览：</strong>
          <pre>{{ previewContent }}</pre>
        </div>
      </section>

      <footer class="step-nav">
        <button v-if="step > 1" class="ghost-btn" @click="prevStep">上一步</button>
        <button v-if="step < 4" class="primary-btn" :disabled="!canNext" @click="nextStep">
          下一步
        </button>
        <button v-else class="primary-btn" :disabled="saving || !has('warnings:create')" @click="submit">
          <Loader2 v-if="saving" :size="14" class="spin" />
          <Send v-else :size="14" />提交发布
        </button>
      </footer>
    </div>

    <div v-else class="success-card">
      <h3><AlertTriangle :size="20" color="#2a8154" />预警已创建</h3>
      <p>预警编号 {{ submitted.id }}，已进入逐级审批流程。</p>
      <div class="actions">
        <button class="ghost-btn" @click="router.push('/dashboard/warnings')">返回预警列表</button>
        <button class="primary-btn" @click="viewSubmitted"><Save :size="14" />查看预警详情</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.warning-create-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; }
.page-header h2 { margin: 4px 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }
.back-btn { padding: 4px 10px; border: 1px solid #d9e6e1; background: #fff; border-radius: 6px; cursor: pointer; font-size: 12px; color: #56716e; display: inline-flex; gap: 4px; align-items: center; }

.step-indicator { display: flex; gap: 8px; }
.step-indicator span { display: flex; gap: 6px; align-items: center; padding: 6px 10px; background: #f4f7f5; border-radius: 16px; font-size: 12px; color: #7a8d89; }
.step-indicator span code { width: 18px; height: 18px; background: #fff; border-radius: 50%; display: grid; place-items: center; font-size: 10px; color: #56716e; }
.step-indicator span.active { background: rgba(47,127,128,.12); color: #2f7f80; font-weight: 600; }
.step-indicator span.active code { background: #2f7f80; color: #fff; }
.step-indicator span.done { background: rgba(93,180,134,.15); color: #2a8154; }
.step-indicator span.done code { background: #2a8154; color: #fff; }

.form-card { background: #fff; border: 1px solid #e9eee9; border-radius: 12px; padding: 24px 28px; max-width: 720px; }
.step-pane label { display: block; margin-bottom: 18px; color: #56716e; font-size: 12px; font-weight: 600; }
.step-pane input, .step-pane textarea, .step-pane select { display: block; width: 100%; margin-top: 6px; padding: 9px 12px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; }
.step-pane input[type=range] { padding: 0; background: transparent; }

.row-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }

.level-pick { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 6px; }
.level-pick button { padding: 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; cursor: pointer; text-align: left; }
.level-pick button.active { border-width: 2px; }
.level-pick button strong { display: block; font-size: 14px; }
.level-pick button small { display: block; color: #7a8d89; font-size: 10px; margin-top: 2px; }

.map-stub { padding: 22px; background: #f4f7f5; border-radius: 10px; text-align: center; color: #56716e; }
.map-stub p { margin: 6px 0 0; font-size: 12px; }

.tpl-list { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 6px; }
.tpl-list button { padding: 10px 12px; border: 1px solid #d9e6e1; background: #fafdfc; border-radius: 8px; cursor: pointer; text-align: left; }
.tpl-list button.active { border-color: #2f7f80; background: rgba(47,127,128,.08); }
.tpl-list button strong { display: block; color: #1f3e44; font-size: 12px; }
.tpl-list button small { display: block; color: #7a8d89; font-size: 11px; margin-top: 2px; }

.channels { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 6px; }
.channel-pick { position: relative; padding: 10px 12px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; display: flex; flex-direction: column; gap: 2px; cursor: pointer; }
.channel-pick input[type=checkbox] { position: absolute; top: 8px; right: 8px; }
.channel-pick.active { border-color: #2f7f80; background: rgba(47,127,128,.06); }
.channel-pick strong { font-size: 12px; color: #1f3e44; }
.channel-pick small { color: #7a8d89; font-size: 10px; }
.channel-pick .rec-tag { position: absolute; bottom: 6px; right: 8px; font-size: 9px; color: #9b761e; padding: 1px 4px; background: rgba(155,118,30,.12); border-radius: 3px; }

.summary { list-style: none; padding: 0; margin: 16px 0; display: flex; flex-direction: column; gap: 8px; }
.summary li { padding: 8px 12px; background: #fafdfc; border-radius: 6px; font-size: 13px; color: #37555a; }
.preview { background: #f4f7f5; padding: 14px; border-radius: 8px; }
.preview pre { white-space: pre-wrap; font-family: inherit; color: #37555a; font-size: 13px; line-height: 1.7; margin: 8px 0 0; }

.step-nav { display: flex; gap: 10px; justify-content: flex-end; padding-top: 18px; border-top: 1px dashed #d9e6e1; margin-top: 24px; }
.primary-btn { display: flex; gap: 6px; align-items: center; padding: 9px 16px; border: 0; background: #2f7f80; color: #fff; border-radius: 8px; cursor: pointer; font-size: 13px; }
.primary-btn:disabled { opacity: .5; }
.ghost-btn { padding: 9px 16px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; color: #37555a; cursor: pointer; font-size: 13px; }

.success-card { max-width: 720px; padding: 32px; background: #fff; border: 1px solid #e9eee9; border-radius: 12px; text-align: center; }
.success-card h3 { margin: 0; color: #1f3e44; display: flex; gap: 8px; align-items: center; justify-content: center; }
.success-card p { color: #7a8d89; margin: 10px 0 24px; }
.success-card .actions { display: flex; gap: 10px; justify-content: center; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
