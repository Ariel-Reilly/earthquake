<script setup>
/**
 * 通知模板管理（独立页面 /dashboard/notice-templates）。
 *
 * 角色：管理员 / 值班员
 * 灾种 × 等级 × 渠道的多变量通知模板维护，支持占位符实时预览。
 */
import { computed, onMounted, reactive, ref, watch } from "vue"
import {
  ClipboardList,
  Copy,
  Edit,
  Filter,
  Info,
  Loader2,
  Mail,
  MessageSquare,
  Plus,
  RefreshCw,
  Send,
  Smartphone,
  Trash2,
  Volume2,
  X,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"
import { getAuthUser, hasPermission } from "../../stores/auth"
import { HAZARD_TYPES, hazardTypeLabel } from "../../utils/hazardTypes"
import ConfirmDialog from '../../components/common/ConfirmDialog.vue'

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const confirmDialogRef = ref(null)

const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }
const has = (perm) => hasPermission(operator.role, perm)

const loading = ref(false)
const loadError = ref("")
const list = ref([])
const filter = reactive({ hazardType: "", level: "", channel: "", status: "" })
const editDrawer = ref(false)
const editing = ref(null)
const activeChannel = ref("sms")
const saving = ref(false)

// 多渠道占位变量 —— 预览时用样例值替换
const placeholderSamples = {
  location: "龙安镇北纬 30°34′ / 东经 104°05′",
  level: "橙色预警",
  startTime: "2026-09-12 14:25",
  hazardName: "龙安镇滑坡隐患点 H-021",
  recipient: "李晨曦",
  region: "四川省成都市龙泉驿区",
  bufferKm: "5",
}

const headerDesc = "按灾种、等级、渠道维护通知文案，支持 {{var}} 占位符实时预览与多渠道分发。"

function formatPlaceholder(key) {
  return `{{${key}}}`
}

const placeholderDocs = Object.keys(placeholderSamples)

// 取值必须与后端 unified_event.hazard_type 一致（landslide / collapse / ...），
// 后端 notice_template.disaster_type 存的同样是英文码；这里原先直接写中文标签，
// 导致筛选参数永远匹配不到库里的记录。
//
// R3：不再在本页各写一份（原为 5 项，缺 typhoon/drought/waterlogging/
// ground_fissure/flood/fire），统一引用与 sys_dict.disaster_type 对齐的单一数据源。
const hazardOptions = HAZARD_TYPES
const levelOptions = [
  { value: "red", label: "红色" },
  { value: "orange", label: "橙色" },
  { value: "yellow", label: "黄色" },
  { value: "blue", label: "蓝色" },
]
const channelOptions = [
  { value: "sms", label: "短信", icon: MessageSquare },
  { value: "email", label: "邮件", icon: Mail },
  { value: "app", label: "APP 推送", icon: Smartphone },
  { value: "sound", label: "声光", icon: Volume2 },
]
const statusOptions = [
  { value: "enabled", label: "启用" },
  { value: "disabled", label: "停用" },
]

const channelMeta = {
  sms: { label: "短信", color: "#6f9e7f", max: 320 },
  email: { label: "邮件", color: "#87b096", max: 2000 },
  app: { label: "APP 推送", color: "#6f9e7f", max: 200 },
  sound: { label: "声光", color: "#cbaa76", max: 80 },
}

const levelColor = {
  red: "#e06060",
  orange: "#cbaa76",
  yellow: "#d9c040",
  blue: "#6f9e7f",
}

// 模板列表全部来自真实 API（dashboardApi.listNoticeTemplates），不再内置样例数据。
async function load() {
  loading.value = true
  loadError.value = ""
  try {
    const data = await dashboardApi.listNoticeTemplates({
      hazardType: filter.hazardType || undefined,
      level: filter.level || undefined,
      channel: filter.channel || undefined,
      status: filter.status || undefined,
    })
    const raw = data?.records ?? data?.items ?? []
    list.value = Array.isArray(raw) ? raw : []
  } catch (e) {
    list.value = []
    loadError.value = e?.message ?? "加载模板失败"
    emit("notify", loadError.value)
  } finally {
    loading.value = false
  }
}

const filteredList = computed(() => list.value)

function levelLabel(lv) {
  return levelOptions.find((o) => o.value === lv)?.label ?? lv
}
function hazardLabel(hz) {
  return hazardTypeLabel(hz)
}
function levelColorOf(lv) {
  return levelColor[lv] ?? "#76847a"
}
function channelList(item) {
  return Object.keys(item.channels ?? {}).filter((k) => channelMeta[k])
}
function channelBadge(channels) {
  return (channels ?? []).map((c) => channelMeta[c]?.label ?? c)
}

function openCreate() {
  editing.value = {
    id: null,
    name: "",
    hazardType: "landslide",
    level: "orange",
    channels: { sms: "", email: "", app: "", sound: "" },
    enabled: true,
    createdBy: operator.name,
    updatedAt: new Date().toISOString().slice(0, 16).replace("T", " "),
  }
  activeChannel.value = "sms"
  editDrawer.value = true
}

function openEdit(item) {
  editing.value = JSON.parse(JSON.stringify(item))
  activeChannel.value = Object.keys(item.channels ?? {})[0] ?? "sms"
  editDrawer.value = true
}

function duplicate(item) {
  editing.value = {
    ...JSON.parse(JSON.stringify(item)),
    id: null,
    name: `${item.name} · 副本`,
    enabled: false,
    updatedAt: new Date().toISOString().slice(0, 16).replace("T", " "),
  }
  activeChannel.value = "sms"
  editDrawer.value = true
  emit("notify", "已复制模板，编辑后可保存为新模板")
}

async function testTemplate(item) {
  try {
    const res = await dashboardApi.testNoticeTemplate(item.id, { channel: "sms" })
    const status = res?.status
    if (status === "BLOCKED") {
      emit("notify", "已拦截：当前仅允许发送给项目测试成员")
    } else if (status === "MOCK_SENT" || status === "PROBE_ONLY") {
      emit("notify", "测试发送已受理，但当前为演示发送，未真实投递")
    } else if (status === "SUCCESS") {
      emit("notify", "测试发送成功，请查看接收端")
    } else if (status) {
      emit("notify", `测试发送未成功：${res?.message ?? status}`)
    } else {
      // 前端 mock 模式下 res.status 为 undefined，不能默认当成成功
      emit("notify", "演示模式未返回发送状态，无法确认是否投递")
    }
  } catch (e) {
    emit("notify", `测试发送失败：${e?.message ?? e}`)
  }
}

async function remove(item) {
  const ok = await confirmDialogRef.value?.show({
    title: '删除通知模板',
    message: `确认删除模板「${item.name}」？`,
    confirmText: '删除',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    await dashboardApi.deleteNoticeTemplate(item.id)
    emit('notify', '模板已删除')
    await load()
  } catch (e) {
    emit('notify', `删除失败：${e?.message ?? e}`)
  }
}

async function toggleEnabled(item) {
  const prev = item.enabled
  item.enabled = !item.enabled
  try {
    await dashboardApi.toggleNoticeTemplate(item.id)
    emit("notify", item.enabled ? "模板已启用" : "模板已停用")
  } catch (e) {
    item.enabled = prev
    emit("notify", `操作失败：${e?.message ?? e}（已恢复原状态）`)
  }
}

function renderPreview(text) {
  if (!text) return ""
  return text.replace(/\{\{(\w+)\}\}/g, (_, key) => placeholderSamples[key] ?? `{{${key}}}`)
}

function insertPlaceholder(key) {
  const ch = activeChannel.value
  if (!editing.value?.channels) return
  editing.value.channels[ch] = (editing.value.channels[ch] ?? "") + `{{${key}}}`
}

async function save() {
  if (!editing.value) return
  if (!editing.value.name.trim()) return emit("notify", "请填写模板名称")
  const anyContent = Object.values(editing.value.channels).some((v) => (v ?? "").trim())
  if (!anyContent) return emit("notify", "请至少填写一个渠道的内容")
  saving.value = true
  const isEdit = !!editing.value.id
  try {
    await dashboardApi.upsertNoticeTemplate({
      id: editing.value.id,
      name: editing.value.name.trim(),
      hazardType: editing.value.hazardType,
      level: editing.value.level,
      channels: editing.value.channels,
      enabled: editing.value.enabled,
    })
    emit("notify", isEdit ? "模板已更新" : "模板已创建")
    editDrawer.value = false
    await load()
  } catch (e) {
    emit("notify", `保存失败：${e?.message ?? e}`)
  } finally {
    saving.value = false
  }
}

const channelCharCount = computed(() => {
  if (!editing.value) return 0
  const text = editing.value.channels?.[activeChannel.value] ?? ""
  return text.length
})
const channelMax = computed(() => channelMeta[activeChannel.value]?.max ?? 200)

watch(
  () => ({ ...filter }),
  () => load(),
  { deep: true },
)

onMounted(load)
</script>

<template>
  <div class="tpl-shell">
    <header class="page-header">
      <div>
      </div>
      <div class="actions">
        <button class="ghost-btn" @click="load"><RefreshCw :size="14" />刷新</button>
        <button v-if="has('notice:edit')" class="primary-btn" @click="openCreate">
          <Plus :size="14" />新建模板
        </button>
      </div>
    </header>

    <div v-if="loadError" class="load-error-hint">
      <Info :size="14" /> 模板加载失败：{{ loadError }}（可点击右上角「刷新」重试）
    </div>

    <section class="filter-bar">
      <Filter :size="14" color="#4a7a5e" />
      <select v-model="filter.hazardType">
        <option value="">全部灾种</option>
        <option v-for="t in hazardOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
      </select>
      <select v-model="filter.level">
        <option value="">全部等级</option>
        <option v-for="o in levelOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
      <select v-model="filter.channel">
        <option value="">全部渠道</option>
        <option v-for="o in channelOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
      <select v-model="filter.status">
        <option value="">全部状态</option>
        <option v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
    </section>

    <div v-if="loading" class="loading-row">
      <Loader2 :size="16" class="spin" />加载模板…
    </div>
    <div v-else-if="filteredList.length === 0" class="empty-row">
      没有匹配的模板 —— 调整筛选或新建模板
    </div>
    <div v-else class="card-grid">
      <article v-for="tpl in filteredList" :key="tpl.id" class="tpl-card" :class="{ disabled: !tpl.enabled }">
        <header class="tpl-head">
          <strong>{{ tpl.name }}</strong>
          <label class="switch" :title="tpl.enabled ? '已启用' : '已停用'">
            <input type="checkbox" :checked="tpl.enabled" @change="toggleEnabled(tpl)" />
            <span class="slider"></span>
          </label>
        </header>
        <div class="tpl-meta">
          <span class="level-pill" :style="{ background: levelColorOf(tpl.level) + '22', color: levelColorOf(tpl.level) }">
            {{ hazardLabel(tpl.hazardType) }} · {{ levelLabel(tpl.level) }}
          </span>
          <div class="channel-badges">
            <span v-for="ch in channelList(tpl)" :key="ch" class="badge" :style="{ background: channelMeta[ch].color + '22', color: channelMeta[ch].color }">
              <component :is="channelMeta[ch] ? channelOptions.find((o) => o.value === ch).icon : MessageSquare" :size="11" />
              {{ channelMeta[ch]?.label ?? ch }}
            </span>
          </div>
        </div>
        <p class="snippet">{{ tpl.channels?.sms || tpl.channels?.app || "（未配置正文）" }}</p>
        <footer>
          <div class="foot-meta">
            <small>创建 {{ tpl.createdBy ?? "—" }}</small>
            <small>修改 {{ tpl.updatedAt }}</small>
          </div>
          <div class="ops">
            <button v-if="has('notice:edit')" class="ghost-btn" @click="testTemplate(tpl)" title="测试发送">
              <Send :size="13" />
            </button>
            <button v-if="has('notice:edit')" class="ghost-btn" @click="openEdit(tpl)" title="编辑">
              <Edit :size="13" />
            </button>
            <button v-if="has('notice:edit')" class="ghost-btn" @click="duplicate(tpl)" title="复制">
              <Copy :size="13" />
            </button>
            <button v-if="has('notice:edit')" class="ghost-btn danger" @click="remove(tpl)" title="删除">
              <Trash2 :size="13" />
            </button>
          </div>
        </footer>
      </article>
    </div>

    <!-- 编辑/复制抽屉 -->
    <div v-if="editDrawer" class="drawer-mask" @click.self="editDrawer = false">
      <aside class="drawer wide">
        <header>
          <h3>{{ editing?.id ? "编辑通知模板" : "新建通知模板" }}</h3>
          <button class="ghost-btn icon" @click="editDrawer = false"><X :size="14" /></button>
        </header>
        <div v-if="editing" class="drawer-body">
          <div class="form-grid">
            <label>模板名称<input v-model="editing.name" placeholder="如：滑坡橙色预警 · 通用模板" /></label>
            <label>灾种
              <select v-model="editing.hazardType">
                <option v-for="t in hazardOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
              </select>
            </label>
            <label>等级
              <select v-model="editing.level">
                <option v-for="o in levelOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </label>
            <label class="switch">
              <input type="checkbox" v-model="editing.enabled" />
              <span>启用此模板</span>
            </label>
          </div>

          <div class="channel-tabs">
            <button
              v-for="ch in channelOptions"
              :key="ch.value"
              :class="{ active: activeChannel === ch.value }"
              @click="activeChannel = ch.value"
            >
              <component :is="ch.icon" :size="13" />{{ ch.label }}
            </button>
          </div>

          <div class="editor-grid">
            <div class="editor-side">
              <label class="editor-label">
                {{ channelMeta[activeChannel].label }} 文案
                <small>{{ channelCharCount }} / {{ channelMax }} 字</small>
              </label>
              <textarea
                v-model="editing.channels[activeChannel]"
                rows="8"
                :placeholder="`例如：【${channelMeta[activeChannel].label}·橙色】…`"
              />
              <div class="placeholder-bar">
                <span class="ph-label">插入变量：</span>
                <button
                  v-for="p in placeholderDocs"
                  :key="p"
                  class="ph-btn"
                  @click="insertPlaceholder(p)"
                >{{ formatPlaceholder(p) }}</button>
              </div>
            </div>
            <div class="preview-side">
              <span class="editor-label">实时预览</span>
              <article class="preview-card" :style="{ borderColor: channelMeta[activeChannel].color }">
                <header :style="{ color: channelMeta[activeChannel].color }">
                  <component
                    :is="channelOptions.find((o) => o.value === activeChannel)?.icon ?? MessageSquare"
                    :size="14"
                  />
                  {{ channelMeta[activeChannel].label }} 预览
                </header>
                <pre>{{ renderPreview(editing.channels[activeChannel] ?? "") || "（暂无内容）" }}</pre>
              </article>
              <div class="legend">
                <small v-for="(v, k) in placeholderSamples" :key="k">
                  <code>{{ formatPlaceholder(k) }}</code> → {{ v }}
                </small>
              </div>
            </div>
          </div>

          <div class="drawer-actions">
            <button class="ghost-btn" @click="editDrawer = false">取消</button>
            <button class="primary-btn" :disabled="saving" @click="save">
              <Loader2 v-if="saving" :size="14" class="spin" />提交
            </button>
          </div>
        </div>
      </aside>
    </div>

    <ConfirmDialog ref="confirmDialogRef" />
  </div>
</template>

<style scoped>
.tpl-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; gap: 12px; flex-wrap: wrap; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: var(--c-text); }
.page-header p { margin: 4px 0 0; color: var(--c-text-2); font-size: 12px; }
.actions { display: flex; gap: 8px; }

.load-error-hint { display: flex; gap: 6px; align-items: center; padding: 8px 14px; background: var(--c-danger-bg); border: 1px solid var(--c-danger-soft); border-radius: var(--r-sm); color: var(--c-danger-strong); font-size: 12px; margin-bottom: 14px; }
.load-error-hint svg { color: var(--c-danger-strong); flex-shrink: 0; }

.primary-btn { display: inline-flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: var(--c-brand); color: var(--c-on-brand); border-radius: var(--r-sm); font-size: 13px; cursor: pointer; }
.primary-btn:disabled { opacity: .5; }
.ghost-btn { display: inline-flex; gap: 4px; align-items: center; padding: 6px 10px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); font-size: 12px; cursor: pointer; }
.ghost-btn:hover { background: var(--c-bg); }
.ghost-btn.icon { padding: 6px; }
.ghost-btn.danger { color: var(--c-danger-strong); border-color: var(--c-danger-soft); }

.filter-bar { display: flex; gap: 10px; align-items: center; background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 12px 16px; margin-bottom: 16px; flex-wrap: wrap; }
.filter-bar select { padding: 7px 10px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface-2); font-size: 13px; min-width: 120px; }

.loading-row, .empty-row { padding: 40px; text-align: center; color: var(--c-text-2); font-size: 13px; }

.card-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; }
.tpl-card { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-lg); padding: 16px; display: flex; flex-direction: column; gap: 10px; transition: box-shadow .2s, transform .2s; }
.tpl-card:hover { box-shadow: 0 8px 20px rgba(0,0,0,.05); transform: translateY(-1px); }
.tpl-card.disabled { opacity: .65; background: var(--c-surface-2); }
.tpl-head { display: flex; gap: 10px; align-items: center; }
.tpl-head strong { flex: 1; color: var(--c-text); font-size: 15px; }
.tpl-meta { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.level-pill { display: inline-block; padding: 3px 10px; border-radius: var(--r-lg); font-size: 11px; font-weight: 600; }
.channel-badges { display: flex; gap: 6px; flex-wrap: wrap; margin-left: auto; }
.badge { display: inline-flex; gap: 4px; align-items: center; padding: 3px 8px; border-radius: var(--r-xs); font-size: 11px; font-weight: 600; }
.snippet { margin: 0; color: var(--c-brand-deep); font-size: 12px; line-height: 1.7; padding: 10px 12px; background: var(--c-surface-2); border-radius: var(--r-sm); border-left: 3px solid var(--c-separator); max-height: 64px; overflow: hidden; text-overflow: ellipsis; }

.tpl-card footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-top: 6px; border-top: 1px dashed var(--c-border-2); }
.foot-meta { display: flex; flex-direction: column; gap: 2px; color: var(--c-text-2); font-size: 11px; }
.ops .ghost-btn { margin-left: 4px; }

.switch { position: relative; display: inline-block; width: 34px; height: 18px; cursor: pointer; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch .slider { position: absolute; inset: 0; background: var(--c-separator); border-radius: var(--r-md); transition: background .2s; }
.switch .slider::before { content: ""; position: absolute; left: 2px; top: 2px; width: 14px; height: 14px; background: var(--c-surface); border-radius: 50%; transition: transform .2s; }
.switch input:checked + .slider { background: var(--c-brand-hover); }
.switch input:checked + .slider::before { transform: translateX(16px); }

.drawer-mask { position: fixed; inset: 0; background: var(--c-overlay); display: flex; justify-content: flex-end; z-index: 110; }
.drawer { width: min(880px, 100%); background: var(--c-surface); height: 100%; display: flex; flex-direction: column; box-shadow: -8px 0 24px rgba(0,0,0,.08); }
.drawer > header { display: flex; align-items: center; justify-content: space-between; padding: 18px 24px; border-bottom: 1px solid var(--c-border-2); }
.drawer > header h3 { margin: 0; font-size: 15px; color: var(--c-text); }
.drawer-body { padding: 22px 24px; overflow-y: auto; flex: 1; }

.form-grid { display: grid; grid-template-columns: 2fr 1fr 1fr auto; gap: 14px; align-items: end; margin-bottom: 18px; }
.form-grid label { display: flex; flex-direction: column; gap: 6px; color: var(--c-brand-deep); font-size: 12px; font-weight: 600; }
.form-grid label.switch { flex-direction: row; align-items: center; gap: 8px; font-size: 13px; color: var(--c-text); font-weight: 500; }
.form-grid label.switch input { width: 14px; height: 14px; accent-color: var(--c-brand); }
.form-grid input, .form-grid select { padding: 8px 10px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface-2); font-size: 13px; }

.channel-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--c-brand-soft); margin-bottom: 18px; }
.channel-tabs button { display: inline-flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: transparent; cursor: pointer; font-size: 13px; color: var(--c-brand-deep); border-bottom: 2px solid transparent; }
.channel-tabs button.active { color: var(--c-brand); border-bottom-color: var(--c-brand); }

.editor-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.editor-side, .preview-side { display: flex; flex-direction: column; gap: 10px; }
.editor-label { display: flex; gap: 8px; justify-content: space-between; color: var(--c-brand-deep); font-size: 12px; font-weight: 600; }
.editor-side textarea { width: 100%; padding: 12px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); font-size: 13px; font-family: inherit; background: var(--c-surface-2); resize: vertical; }

.placeholder-bar { display: flex; gap: 6px; flex-wrap: wrap; padding: 8px; border: 1px dashed var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface-2); }
.ph-label { color: var(--c-text-2); font-size: 11px; margin-right: 4px; }
.ph-btn { padding: 3px 8px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-xs); font-size: 11px; font-family: monospace; cursor: pointer; color: var(--c-brand); }
.ph-btn:hover { background: var(--c-brand-mist); }

.preview-card { border: 1px solid; border-radius: var(--r-md); padding: 14px; background: var(--c-surface); }
.preview-card header { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; font-size: 13px; font-weight: 600; }
.preview-card pre { margin: 0; padding: 12px; background: var(--c-surface-2); border-radius: var(--r-sm); font-family: inherit; font-size: 13px; line-height: 1.7; color: var(--c-text); white-space: pre-wrap; word-break: break-all; max-height: 200px; overflow-y: auto; }

.legend { display: flex; flex-direction: column; gap: 4px; padding: 8px 10px; background: var(--c-surface-2); border-radius: var(--r-sm); border: 1px solid var(--c-brand-soft); }
.legend small { color: var(--c-brand-deep); font-size: 11px; }
.legend code { background: var(--c-surface); padding: 1px 5px; border-radius: 3px; font-size: 11px; color: var(--c-brand); margin-right: 6px; }

.drawer-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 24px; padding-top: 16px; border-top: 1px solid var(--c-border-2); }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

@media (max-width: 880px) {
  .card-grid { grid-template-columns: 1fr; }
  .form-grid { grid-template-columns: 1fr 1fr; }
  .form-grid label.switch { grid-column: 1 / -1; }
  .editor-grid { grid-template-columns: 1fr; }
}
@media (max-width: 540px) {
  .tpl-shell { padding: 16px; }
  .form-grid { grid-template-columns: 1fr; }
  .actions { width: 100%; }
  .actions button { flex: 1; justify-content: center; }
}
</style>
