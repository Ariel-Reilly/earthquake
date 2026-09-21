<script setup>
/**
 * 规则编辑器组件
 */
import { computed, onMounted, reactive, ref, watch } from "vue"
import {
  Code,
  Loader2,
  Plus,
  Play,
  Save,
  TestTube2,
  Trash2,
  X,
} from "lucide-vue-next"

const props = defineProps({
  rule: { type: Object, default: null },
})

const emit = defineEmits(["save", "test", "notify"])

const isEdit = computed(() => props.rule && props.rule.id)

const form = reactive({
  name: "",
  disasterType: "landslide",
  level: "orange",
  priority: 10,
  conditions: {
    operator: "AND",
    items: [],
  },
  actions: [],
  dslText: "",
  enabled: true,
})

const disasterOptions = [
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
  { value: "red", label: "红色" },
  { value: "orange", label: "橙色" },
  { value: "yellow", label: "黄色" },
  { value: "blue", label: "蓝色" },
]

const levelColor = {
  red: "#e06060",
  orange: "#e0a050",
  yellow: "#d9c040",
  blue: "#6dbfa5",
}

const actionTypes = [
  { value: "CREATE_EVENT", label: "生成疑似事件" },
  { value: "PUSH_CONFIRM", label: "推送人工确认" },
  { value: "SEND_NOTIFICATION", label: "发送通知" },
]

const operatorOptions = [
  { value: ">", label: "大于" },
  { value: "<", label: "小于" },
  { value: ">=", label: "大于等于" },
  { value: "<=", label: "小于等于" },
  { value: "==", label: "等于" },
  { value: "!=", label: "不等于" },
]

const fieldOptions = [
  { value: "rainfall_24h", label: "降雨量 (24h)", unit: "mm", source: "weather" },
  { value: "rainfall_7d", label: "降雨量 (7d)", unit: "mm", source: "weather" },
  { value: "slope", label: "坡度", unit: "°", source: "terrain" },
  { value: "displacement_rate", label: "位移速率", unit: "mm/d", source: "device" },
  { value: "soil_moisture", label: "土壤湿度", unit: "%", source: "device" },
  { value: "water_level", label: "河流水位", unit: "m", source: "device" },
  { value: "earthquake_mag", label: "地震震级", unit: "M", source: "usgs" },
  { value: "temperature", label: "温度", unit: "°C", source: "weather" },
  { value: "crack_width", label: "裂缝宽度", unit: "mm", source: "device" },
  { value: "tilt_angle", label: "倾斜角", unit: "°", source: "device" },
]

const dslMode = ref(false)
const saving = ref(false)
const testing = ref(false)

function initForm() {
  if (props.rule) {
    Object.assign(form, JSON.parse(JSON.stringify(props.rule)))
  } else {
    form.name = ""
    form.disasterType = "landslide"
    form.level = "orange"
    form.priority = 10
    form.conditions = { operator: "AND", items: [] }
    form.actions = []
    form.dslText = ""
    form.enabled = true
  }
}

function addCondition() {
  form.conditions.items.push({
    field: "rainfall_24h",
    op: ">",
    value: 0,
    unit: "mm",
  })
  updateDsl()
}

function removeCondition(index) {
  form.conditions.items.splice(index, 1)
  updateDsl()
}

function addAction() {
  form.actions.push({
    type: "CREATE_EVENT",
    params: {},
  })
}

function removeAction(index) {
  form.actions.splice(index, 1)
}

function updateDsl() {
  if (form.conditions.items.length === 0) {
    form.dslText = ""
    return
  }

  const conditions = form.conditions.items.map(item => {
    const field = fieldOptions.find(f => f.value === item.field)
    const label = field?.label?.split(" ")[0] || item.field
    const unit = field?.unit || ""
    return `${item.field} ${item.op} ${item.value}${unit}`
  })

  form.dslText = conditions.join(` ${form.conditions.operator} `)
}

function toggleOperator() {
  form.conditions.operator = form.conditions.operator === "AND" ? "OR" : "AND"
  updateDsl()
}

async function save() {
  if (!form.name.trim()) {
    emit("notify", "请填写规则名称")
    return
  }
  if (form.conditions.items.length === 0) {
    emit("notify", "请至少添加一个条件")
    return
  }

  saving.value = true
  try {
    const { createRule, updateRule } = await import("../../services/ruleEngineService")
    const payload = { ...form }
    if (isEdit.value) {
      await updateRule(props.rule.id, payload)
      emit("notify", "规则已更新")
    } else {
      await createRule(payload)
      emit("notify", "规则已创建")
    }
    emit("save", payload)
  } catch (e) {
    emit("notify", "保存失败: " + e.message)
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  try {
    const { testRule } = await import("../../services/ruleEngineService")
    const request = {
      ruleId: props.rule?.id,
      conditions: form.conditions,
      inputData: {},
    }
    const result = await testRule(request)
    emit("test", result)
  } catch (e) {
    emit("notify", "测试失败: " + e.message)
  } finally {
    testing.value = false
  }
}

function getFieldUnit(field) {
  return fieldOptions.find(f => f.value === field)?.unit || ""
}

watch(() => form.conditions.items, () => updateDsl(), { deep: true })

onMounted(initForm)
watch(() => props.rule, initForm)
</script>

<template>
  <div class="rule-editor">
    <div class="editor-header">
      <h3>{{ isEdit ? "编辑规则" : "新建规则" }}</h3>
      <div class="header-actions">
        <label class="switch-label">
          <span>启用</span>
          <label class="switch">
            <input type="checkbox" v-model="form.enabled" />
            <span class="slider"></span>
          </label>
        </label>
      </div>
    </div>

    <div class="editor-body">
      <!-- 基本信息 -->
      <div class="form-section">
        <h4>基本信息</h4>
        <div class="form-grid">
          <label class="form-item">
            <span>规则名称 *</span>
            <input v-model="form.name" placeholder="例如：滑坡橙色预警规则" />
          </label>
          <label class="form-item">
            <span>灾种</span>
            <select v-model="form.disasterType">
              <option v-for="o in disasterOptions" :key="o.value" :value="o.value">
                {{ o.label }}
              </option>
            </select>
          </label>
          <label class="form-item">
            <span>预警级别</span>
            <select v-model="form.level">
              <option v-for="o in levelOptions" :key="o.value" :value="o.value">
                {{ o.label }}
              </option>
            </select>
          </label>
          <label class="form-item">
            <span>优先级</span>
            <input type="number" v-model.number="form.priority" min="0" max="100" />
          </label>
        </div>
      </div>

      <!-- 触发条件 -->
      <div class="form-section">
        <div class="section-header">
          <h4>触发条件</h4>
          <button class="toggle-op-btn" @click="toggleOperator">
            {{ form.conditions.operator }}
          </button>
        </div>

        <div v-if="!dslMode" class="conditions-visual">
          <div
            v-for="(item, index) in form.conditions.items"
            :key="index"
            class="condition-row"
          >
            <span v-if="index > 0" class="op-label">{{ form.conditions.operator }}</span>
            <select v-model="item.field" @change="item.unit = getFieldUnit(item.field)">
              <option v-for="o in fieldOptions" :key="o.value" :value="o.value">
                {{ o.label }}
              </option>
            </select>
            <select v-model="item.op">
              <option v-for="o in operatorOptions" :key="o.value" :value="o.value">
                {{ o.label }}
              </option>
            </select>
            <input type="number" v-model.number="item.value" step="0.1" />
            <span class="unit-label">{{ item.unit }}</span>
            <button class="icon-btn danger" @click="removeCondition(index)">
              <Trash2 :size="14" />
            </button>
          </div>
          <button class="add-btn" @click="addCondition">
            <Plus :size="14" />
            添加条件
          </button>
        </div>

        <div v-else class="dsl-editor">
          <label>
            <span>DSL 表达式</span>
            <textarea
              v-model="form.dslText"
              rows="4"
              placeholder="rainfall_24h > 50 && slope > 30"
            ></textarea>
          </label>
        </div>

        <button class="mode-toggle" @click="dslMode = !dslMode">
          <Code :size="14" />
          {{ dslMode ? "切换可视化模式" : "切换 DSL 模式" }}
        </button>
      </div>

      <!-- DSL 预览 -->
      <div v-if="form.dslText" class="form-section dsl-preview">
        <h4>DSL 预览</h4>
        <code>{{ form.dslText }}</code>
      </div>

      <!-- 动作配置 -->
      <div class="form-section">
        <h4>触发动作</h4>
        <div class="actions-list">
          <div
            v-for="(action, index) in form.actions"
            :key="index"
            class="action-row"
          >
            <select v-model="action.type">
              <option v-for="o in actionTypes" :key="o.value" :value="o.value">
                {{ o.label }}
              </option>
            </select>
            <input
              v-if="action.type === 'SEND_NOTIFICATION'"
              v-model="action.params.template"
              placeholder="模板ID"
            />
            <button class="icon-btn danger" @click="removeAction(index)">
              <Trash2 :size="14" />
            </button>
          </div>
          <button class="add-btn" @click="addAction">
            <Plus :size="14" />
            添加动作
          </button>
        </div>
      </div>
    </div>

    <div class="editor-footer">
      <button class="ghost-btn" @click="$emit('cancel')">
        取消
      </button>
      <button class="test-btn" @click="test" :disabled="testing">
        <Loader2 v-if="testing" :size="14" class="spin" />
        <TestTube2 v-else :size="14" />
        测试
      </button>
      <button class="primary-btn" @click="save" :disabled="saving">
        <Loader2 v-if="saving" :size="14" class="spin" />
        <Save v-else :size="14" />
        {{ isEdit ? "保存修改" : "创建规则" }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.rule-editor {
  background: #fff;
  border: 1px solid #e9eee9;
  border-radius: 12px;
  overflow: hidden;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e9eee9;
  background: #fafdfc;
}

.editor-header h3 {
  margin: 0;
  font-size: 16px;
  color: #1f3e44;
}

.switch-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #56716e;
}

.editor-body {
  padding: 20px;
}

.form-section {
  margin-bottom: 24px;
}

.form-section h4 {
  margin: 0 0 12px;
  font-size: 13px;
  color: #37555a;
  font-weight: 600;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.form-grid {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  gap: 14px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-item span {
  font-size: 12px;
  color: #56716e;
  font-weight: 600;
}

.form-item input,
.form-item select {
  padding: 8px 10px;
  border: 1px solid #d9e6e1;
  border-radius: 6px;
  font-size: 13px;
  background: #fafdfc;
}

.toggle-op-btn {
  padding: 4px 12px;
  border: 1px solid #2f7f80;
  background: #fff;
  color: #2f7f80;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.conditions-visual {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.condition-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.op-label {
  padding: 4px 10px;
  background: #2f7f80;
  color: #fff;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

.condition-row select,
.condition-row input {
  padding: 7px 10px;
  border: 1px solid #d9e6e1;
  border-radius: 6px;
  font-size: 13px;
  background: #fafdfc;
}

.unit-label {
  font-size: 12px;
  color: #99a7a1;
  min-width: 30px;
}

.dsl-editor label {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.dsl-editor textarea {
  width: 100%;
  padding: 12px;
  border: 1px solid #d9e6e1;
  border-radius: 6px;
  font-family: monospace;
  font-size: 13px;
  resize: vertical;
}

.dsl-preview {
  background: #f8fbfa;
  padding: 14px;
  border-radius: 8px;
}

.dsl-preview code {
  display: block;
  font-family: monospace;
  font-size: 13px;
  color: #2b6cb0;
  word-break: break-all;
}

.mode-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding: 5px 12px;
  border: 1px dashed #d9e6e1;
  background: #fff;
  border-radius: 6px;
  color: #56716e;
  font-size: 12px;
  cursor: pointer;
}

.mode-toggle:hover {
  background: #f4f7f5;
}

.actions-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.action-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.action-row select {
  flex: 1;
  padding: 7px 10px;
  border: 1px solid #d9e6e1;
  border-radius: 6px;
  font-size: 13px;
  background: #fafdfc;
}

.action-row input {
  flex: 1;
  padding: 7px 10px;
  border: 1px solid #d9e6e1;
  border-radius: 6px;
  font-size: 13px;
  background: #fafdfc;
}

.add-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border: 1px dashed #d9e6e1;
  background: #fff;
  border-radius: 6px;
  color: #2f7f80;
  font-size: 12px;
  cursor: pointer;
}

.add-btn:hover {
  background: #e8f5f3;
}

.editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 20px;
  border-top: 1px solid #e9eee9;
  background: #fafdfc;
}

.primary-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: none;
  background: #2f7f80;
  color: #fff;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.test-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid #2f7f80;
  background: #fff;
  color: #2f7f80;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.ghost-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid #d9e6e1;
  background: #fff;
  border-radius: 6px;
  color: #37555a;
  font-size: 13px;
  cursor: pointer;
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
  border-radius: 4px;
  cursor: pointer;
}

.icon-btn:hover {
  background: #f0f5f4;
}

.icon-btn.danger {
  color: #c7553b;
}

.switch {
  position: relative;
  display: inline-block;
  width: 36px;
  height: 20px;
}

.switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.switch .slider {
  position: absolute;
  inset: 0;
  background: #d9e6e1;
  border-radius: 10px;
  transition: background 0.2s;
  cursor: pointer;
}

.switch .slider::before {
  content: "";
  position: absolute;
  left: 2px;
  top: 2px;
  width: 16px;
  height: 16px;
  background: #fff;
  border-radius: 50%;
  transition: transform 0.2s;
}

.switch input:checked + .slider {
  background: #2f7f80;
}

.switch input:checked + .slider::before {
  transform: translateX(16px);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
