<script setup>
/**
 * 规则列表组件
 */
import { computed, onMounted, ref, watch } from "vue"
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
  try {
    const { getRules } = await import("../../services/ruleEngineService")
    const result = await getRules({
      ...filter.value,
      enabled: filter.value.enabled === "" ? undefined : filter.value.enabled === "true",
      page: page.value,
      size: size.value,
    })
    rules.value = result.records || []
    total.value = result.total || 0
  } catch (e) {
    emit("notify", "加载规则列表失败: " + e.message)
    // 使用示例数据
    rules.value = getMockRules()
    total.value = rules.value.length
  } finally {
    loading.value = false
  }
}

function getMockRules() {
  return [
    {
      id: 1,
      name: "滑坡橙色预警规则",
      disasterType: "滑坡",
      level: "orange",
      priority: 10,
      enabled: true,
      conditions: {
        operator: "AND",
        items: [
          { field: "rainfall_24h", op: ">", value: 50, unit: "mm" },
          { field: "slope", op: ">", value: 30, unit: "°" },
        ],
      },
      actions: [
        { type: "CREATE_EVENT", params: {} },
        { type: "SEND_NOTIFICATION", params: { template: "TPL_LANDSLIDE_ORANGE" } },
      ],
      version: 1,
      updatedAt: "2026-09-10 14:30",
    },
    {
      id: 2,
      name: "洪水红色预警规则",
      disasterType: "洪水",
      level: "red",
      priority: 20,
      enabled: true,
      conditions: {
        operator: "AND",
        items: [
          { field: "rainfall_24h", op: ">", value: 100, unit: "mm" },
          { field: "water_level", op: ">", value: 30, unit: "m" },
        ],
      },
      actions: [
        { type: "PUSH_CONFIRM", params: {} },
        { type: "SEND_NOTIFICATION", params: { template: "TPL_FLOOD_RED" } },
      ],
      version: 2,
      updatedAt: "2026-09-08 09:15",
    },
  ]
}

function levelColor(level) {
  const colors = { red: "#e06060", orange: "#e0a050", yellow: "#d9c040", blue: "#6dbfa5" }
  return colors[level] || "#7a8d89"
}

function levelLabel(level) {
  const labels = { red: "红色", orange: "橙色", yellow: "黄色", blue: "蓝色" }
  return labels[level] || level || "—"
}

async function handleToggle(rule) {
  try {
    const { toggleRule } = await import("../../services/ruleEngineService")
    await toggleRule(rule.id)
    rule.enabled = !rule.enabled
    emit("notify", rule.enabled ? "规则已启用" : "规则已停用")
  } catch (_e) {
    // Mock 模式
    rule.enabled = !rule.enabled
    emit("notify", rule.enabled ? "规则已启用" : "规则已停用")
  }
}

async function handleDelete(rule) {
  if (!confirm(`确认删除规则「${rule.name}」？`)) return
  try {
    const { deleteRule } = await import("../../services/ruleEngineService")
    await deleteRule(rule.id)
    emit("notify", "规则已删除")
    await load()
  } catch (_e) {
    // Mock 模式
    rules.value = rules.value.filter(r => r.id !== rule.id)
    emit("notify", "规则已删除")
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
      <Filter :size="14" color="#56716e" />
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
</template>

<style scoped>
.rule-list {
  background: #fff;
  border: 1px solid #e9eee9;
  border-radius: 12px;
  overflow: hidden;
}

.filter-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid #e9eee9;
  background: #fafdfc;
  flex-wrap: wrap;
}

.filter-bar select {
  padding: 7px 10px;
  border: 1px solid #d9e6e1;
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
}

.loading-state,
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: #7a8d89;
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
  border-bottom: 1px solid #f0f5f4;
}

.rule-table th {
  background: #f8fbfa;
  color: #6f8482;
  font-size: 12px;
  font-weight: 600;
}

.rule-table tr:hover {
  background: #fafdfc;
}

.rule-table tr.disabled {
  opacity: 0.6;
}

.rule-table td strong {
  color: #1f3e44;
}

.disaster-tag {
  display: inline-block;
  padding: 2px 8px;
  background: #e8f5f3;
  color: #2f7f80;
  border-radius: 4px;
  font-size: 12px;
  margin-right: 6px;
}

.level-pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
}

.priority-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  padding: 2px 8px;
  background: #f0f5f4;
  color: #56716e;
  border-radius: 4px;
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
  border-radius: 4px;
  font-size: 12px;
}

.status-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
}

.status-badge.active {
  background: #ecf8f2;
  color: #38a169;
}

.status-badge.inactive {
  background: #f0f5f4;
  color: #99a7a1;
}

.version-badge {
  font-size: 11px;
  color: #99a7a1;
}

.muted {
  color: #99a7a1;
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
  color: #56716e;
  border-radius: 6px;
  cursor: pointer;
}

.icon-btn:hover {
  background: #f0f5f4;
}

.icon-btn.danger {
  color: #c7553b;
}

.icon-btn.danger:hover {
  background: #fbecec;
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 14px;
  border-top: 1px solid #e9eee9;
}

.page-btn {
  padding: 6px 14px;
  border: 1px solid #d9e6e1;
  background: #fff;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.page-btn:hover:not(:disabled) {
  background: #f4f7f5;
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-info {
  font-size: 13px;
  color: #56716e;
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
  border: 1px solid #d9e6e1;
  background: #fff;
  border-radius: 6px;
  color: #37555a;
  font-size: 12px;
  cursor: pointer;
}

.ghost-btn:hover {
  background: #f4f7f5;
}
</style>
