<script setup>
/**
 * 规则列表组件
 */
import { computed, onMounted, ref, watch } from "vue"
import ConfirmDialog from '../common/ConfirmDialog.vue'
import {
  Check,
  Copy,
  Edit,
  Filter,
  Loader2,
  Pause,
  Play,
  RefreshCw,
  Trash2,
  X,
} from "lucide-vue-next"

const props = defineProps({
  filters: { type: Object, default: () => ({}) },
})

const emit = defineEmits(["edit", "delete", "toggle", "duplicate", "notify"])

const loading = ref(false)
const rules = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loadError = ref("")

// 过滤选项
const disasterOptions = [
  { value: "", label: "全部灾种" },
  { value: "landslide", label: "滑坡" },
  { value: "flood", label: "洪水" },
  { value: "earthquake", label: "地震" },
  { value: "fire", label: "火灾" },
  { value: "typhoon", label: "台风" },
  { value: "collapse", label: "崩塌" },
  { value: "debris", label: "泥石流" },
  { value: "settlement", label: "地面沉降" },
]

const levelOptions = [
  { value: "", label: "全部级别" },
  { value: "red", label: "红色" },
  { value: "orange", label: "橙色" },
  { value: "yellow", label: "黄色" },
  { value: "blue", label: "蓝色" },
]

const statusOptions = [
  { value: "", label: "全部状态" },
  { value: "true", label: "已启用" },
  { value: "false", label: "已停用" },
]

const filter = ref({
  disasterType: "",
  level: "",
  enabled: "",
})

async function load() {
  loading.value = true
  loadError.value = ""
  try {
    const { getRules } = await import("../../services/_architecture/domain/ruleEngineService")
    const result = await getRules({
      ...filter.value,
      enabled: filter.value.enabled === "" ? undefined : filter.value.enabled === "true",
      page: page.value,
      size: size.value,
    })
    rules.value = result.records || []
    total.value = result.total || 0
  } catch (e) {
    // 不再静默降级到示例数据：假规则会让用户基于错误前提做判断。
    // 明确置空并进入错误态，由模板展示"加载失败 + 重试"。
    emit("notify", "加载规则列表失败: " + e.message)
    rules.value = []
    total.value = 0
    loadError.value = e.message || "规则列表加载失败"
  } finally {
    loading.value = false
  }
}

function levelColor(level) {
  const colors = { red: "#e06060", orange: "#cbaa76", yellow: "#d9c040", blue: "#87b096" }
  return colors[level] || "#76847a"
}

function levelLabel(level) {
  const labels = { red: "红色", orange: "橙色", yellow: "黄色", blue: "蓝色" }
  return labels[level] || level || "—"
}

async function handleToggle(rule) {
  try {
    const { toggleRule } = await import("../../services/_architecture/domain/ruleEngineService")
    await toggleRule(rule.id)
    rule.enabled = !rule.enabled
    emit("notify", rule.enabled ? "规则已启用" : "规则已停用")
  } catch (e) {
    emit("notify", `操作失败：${e?.message ?? e}`)
  }
}

async function handleDelete(rule) {
  if (!confirm(`确认删除规则「${rule.name}」？`)) return
  try {
    const { deleteRule } = await import("../../services/_architecture/domain/ruleEngineService")
    await deleteRule(rule.id)
    emit("notify", "规则已删除")
    await load()
  } catch (e) {
    emit("notify", `删除失败：${e?.message ?? e}`)
  }
}

function handleEdit(rule) {
  emit("edit", rule)
}

function handleDuplicate(rule) {
  emit("duplicate", { ...rule, id: null, name: `${rule.name} · 副本`, enabled: false })
}

function handlePageChange(newPage) {
  page.value = newPage
  load()
}

watch(filter, () => {
  page.value = 1
  load()
}, { deep: true })

onMounted(load)

defineExpose({ load })
</script>

<template>
  <div class="rule-list">
    <div class="filter-bar">
      <Filter :size="14" color="#4a7a5e" />
      <select v-model="filter.disasterType">
        <option v-for="o in disasterOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
      <select v-model="filter.level">
        <option v-for="o in levelOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
      <select v-model="filter.enabled">
        <option v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
      <button class="ghost-btn" @click="load">
        <RefreshCw :size="14" :class="{ spin: loading }" />
        刷新
      </button>
    </div>

    <div v-if="loading" class="loading-state">
      <Loader2 :size="16" class="spin" />
      加载规则...
    </div>

    <div v-else-if="loadError" class="empty-state">
      <p>规则列表加载失败：{{ loadError }}</p>
      <button class="retry-btn" @click="load">重试</button>
    </div>

    <div v-else-if="rules.length === 0" class="empty-state">
      没有匹配的规则 —— 调整筛选或新建规则
    </div>

    <div v-else class="rule-table">
      <table>
        <thead>
          <tr>
            <th>规则名称</th>
            <th>灾种 / 级别</th>
            <th>优先级</th>
            <th>条件数</th>
            <th>动作数</th>
            <th>状态</th>
            <th>版本</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="rule in rules" :key="rule.id" :class="{ disabled: !rule.enabled }">
            <td>
              <strong>{{ rule.name }}</strong>
            </td>
            <td>
              <span class="disaster-tag">{{ rule.disasterType }}</span>
              <span
                class="level-pill"
                :style="{ background: levelColor(rule.level) + '22', color: levelColor(rule.level) }"
              >
                {{ levelLabel(rule.level) }}
              </span>
            </td>
            <td>
              <span class="priority-badge">{{ rule.priority || 0 }}</span>
            </td>
            <td>
              <span class="count-badge">{{ rule.conditions?.items?.length || 0 }}</span>
            </td>
            <td>
              <span class="count-badge">{{ rule.actions?.length || 0 }}</span>
            </td>
            <td>
              <span class="status-badge" :class="rule.enabled ? 'active' : 'inactive'">
                {{ rule.enabled ? "已启用" : "已停用" }}
              </span>
            </td>
            <td>
              <span class="version-badge">v{{ rule.version || 1 }}</span>
            </td>
            <td>
              <small class="muted">{{ rule.updatedAt || rule.createdAt || "—" }}</small>
            </td>
            <td>
              <div class="action-btns">
                <button class="icon-btn" title="编辑" @click="handleEdit(rule)">
                  <Edit :size="14" />
                </button>
                <button class="icon-btn" title="复制" @click="handleDuplicate(rule)">
                  <Copy :size="14" />
                </button>
                <button
                  class="icon-btn"
                  :title="rule.enabled ? '停用' : '启用'"
                  @click="handleToggle(rule)"
                >
                  <Pause v-if="rule.enabled" :size="14" />
                  <Play v-else :size="14" />
                </button>
                <button class="icon-btn danger" title="删除" @click="handleDelete(rule)">
                  <Trash2 :size="14" />
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="total > size" class="pagination">
      <button
        class="page-btn"
        :disabled="page <= 1"
        @click="handlePageChange(page - 1)"
      >
        上一页
      </button>
      <span class="page-info">{{ page }} / {{ Math.ceil(total / size) }}</span>
      <button
        class="page-btn"
        :disabled="page >= Math.ceil(total / size)"
        @click="handlePageChange(page + 1)"
      >
        下一页
      </button>
    </div>
  </div>
    <ConfirmDialog ref="confirmDialogRef" />
</template>

<style scoped>
.rule-list {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.filter-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--c-brand-soft);
  background: var(--c-surface-2);
  flex-wrap: wrap;
}

.filter-bar select {
  padding: 7px 10px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: 13px;
}

.loading-state,
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: var(--c-text-2);
}

.retry-btn {
  padding: 4px 12px;
  border: 1px solid #cfe0dd;
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-brand);
  font-size: 12px;
  cursor: pointer;
}

.retry-btn:hover {
  background: #eef7f5;
}

.rule-table {
  overflow-x: auto;
}

.rule-table table {
  width: 100%;
  border-collapse: collapse;
}

.rule-table th,
.rule-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--c-brand-mist);
}

.rule-table th {
  background: rgba(60,60,67,.04);
  color: var(--c-text-3);
  font-size: 12px;
  font-weight: 600;
}

.rule-table tr:hover {
  background: var(--c-surface-2);
}

.rule-table tr.disabled {
  opacity: 0.6;
}

.rule-table td strong {
  color: var(--c-text);
}

.disaster-tag {
  display: inline-block;
  padding: 2px 8px;
  background: var(--c-brand-mist);
  color: var(--c-brand);
  border-radius: var(--r-xs);
  font-size: 12px;
  margin-right: 6px;
}

.level-pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--r-lg);
  font-size: 11px;
  font-weight: 600;
}

.priority-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  padding: 2px 8px;
  background: var(--c-brand-mist);
  color: var(--c-brand-deep);
  border-radius: var(--r-xs);
  font-size: 12px;
  font-weight: 600;
}

.count-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  padding: 2px 8px;
  background: #eff0f8;
  color: #5a67d8;
  border-radius: var(--r-xs);
  font-size: 12px;
}

.status-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: var(--r-lg);
  font-size: 11px;
  font-weight: 600;
}

.status-badge.active {
  background: var(--c-brand-mist);
  color: var(--c-success);
}

.status-badge.inactive {
  background: var(--c-brand-mist);
  color: var(--c-text-3);
}

.version-badge {
  font-size: 11px;
  color: var(--c-text-3);
}

.muted {
  color: var(--c-text-3);
}

.action-btns {
  display: flex;
  gap: 4px;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--c-brand-deep);
  border-radius: var(--r-sm);
  cursor: pointer;
}

.icon-btn:hover {
  background: var(--c-brand-mist);
}

.icon-btn.danger {
  color: var(--c-danger-strong);
}

.icon-btn.danger:hover {
  background: var(--c-danger-bg);
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 14px;
  border-top: 1px solid var(--c-brand-soft);
}

.page-btn {
  padding: 6px 14px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface);
  border-radius: var(--r-sm);
  font-size: 13px;
  cursor: pointer;
}

.page-btn:hover:not(:disabled) {
  background: var(--c-bg);
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-info {
  font-size: 13px;
  color: var(--c-brand-deep);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.ghost-btn {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 6px 10px;
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
</style>
