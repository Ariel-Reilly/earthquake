<script setup>
/**
 * 规则测试面板组件
 */
import { computed, onMounted, reactive, ref } from "vue"
import { Check, Loader2, Play, X } from "lucide-vue-next"

const props = defineProps({
  rule: { type: Object, required: true },
})

const emit = defineEmits(["close", "notify"])

const testing = ref(false)
const result = ref(null)

const testData = reactive({
  rainfall_24h: 55,
  rainfall_7d: 150,
  slope: 35,
  displacement_rate: 6,
  soil_moisture: 0.82,
  water_level: 28,
  earthquake_mag: 0,
  temperature: 25,
  crack_width: 8,
  tilt_angle: 2,
})

const fieldOptions = [
  { value: "rainfall_24h", label: "降雨量 (24h)", unit: "mm" },
  { value: "rainfall_7d", label: "降雨量 (7d)", unit: "mm" },
  { value: "slope", label: "坡度", unit: "°" },
  { value: "displacement_rate", label: "位移速率", unit: "mm/d" },
  { value: "soil_moisture", label: "土壤湿度", unit: "%" },
  { value: "water_level", label: "河流水位", unit: "m" },
  { value: "earthquake_mag", label: "地震震级", unit: "M" },
  { value: "temperature", label: "温度", unit: "°C" },
  { value: "crack_width", label: "裂缝宽度", unit: "mm" },
  { value: "tilt_angle", label: "倾斜角", unit: "°" },
]

const fieldMap = Object.fromEntries(fieldOptions.map(f => [f.value, f]))

async function runTest() {
  testing.value = true
  result.value = null

  try {
    const { testRule } = await import("../../services/ruleEngineService")
    const request = {
      ruleId: props.rule.id,
      inputData: { ...testData },
    }
    result.value = await testRule(request)
  } catch (_e) {
    // Mock 模式：本地计算
    const matched = []
    const failed = []

    const conditions = props.rule.conditions?.items || []
    for (const cond of conditions) {
      const actual = testData[cond.field]
      const threshold = cond.value
      let passed = false

      switch (cond.op) {
        case ">": passed = actual > threshold; break
        case "<": passed = actual < threshold; break
        case ">=": passed = actual >= threshold; break
        case "<=": passed = actual <= threshold; break
        case "==": passed = actual === threshold; break
        case "!=": passed = actual !== threshold; break
      }

      const unit = fieldMap[cond.field]?.unit || ""
      const desc = `${cond.field} ${cond.op} ${threshold}${unit}（实际: ${actual}${unit}）`

      if (passed) matched.push(desc)
      else failed.push(desc)
    }

    const triggered = matched.length === conditions.length && conditions.length > 0

    result.value = {
      triggered,
      message: triggered
        ? `条件满足（${matched.length}/${conditions.length}）`
        : `条件不满足（满足 ${matched.length} 项，失败 ${failed.length} 项）`,
      matchedConditions: matched,
      failedConditions: failed,
      actionResults: triggered
        ? [{ type: "MOCK", executed: true, result: "测试模式下动作不执行" }]
        : [],
    }
  } finally {
    testing.value = false
  }
}

function close() {
  emit("close")
}
</script>

<template>
  <div class="test-panel">
    <header class="panel-header">
      <div>
        <h4>规则测试</h4>
        <small>{{ rule.name }}</small>
      </div>
      <button class="close-btn" @click="close">
        <X :size="16" />
      </button>
    </header>

    <div class="panel-body">
      <div class="test-data">
        <h5>输入数据</h5>
        <div class="data-grid">
          <label v-for="field in fieldOptions" :key="field.value" class="data-item">
            <span>{{ field.label }}</span>
            <div class="input-with-unit">
              <input
                type="number"
                v-model.number="testData[field.value]"
                step="0.1"
              />
              <span class="unit">{{ field.unit }}</span>
            </div>
          </label>
        </div>
      </div>

      <div class="test-actions">
        <button class="run-btn" @click="runTest" :disabled="testing">
          <Loader2 v-if="testing" :size="16" class="spin" />
          <Play v-else :size="16" />
          运行测试
        </button>
      </div>

      <div v-if="result" class="test-result">
        <h5>测试结果</h5>
        <div class="result-summary" :class="{ passed: result.triggered, failed: !result.triggered }">
          <div class="result-icon">
            <Check v-if="result.triggered" :size="20" />
            <X v-else :size="20" />
          </div>
          <div class="result-text">
            <strong>{{ result.triggered ? "条件触发" : "条件未触发" }}</strong>
            <span>{{ result.message }}</span>
          </div>
        </div>

        <div v-if="result.matchedConditions?.length" class="condition-list">
          <h6>满足的条件</h6>
          <ul>
            <li v-for="(cond, i) in result.matchedConditions" :key="i" class="passed">
              {{ cond }}
            </li>
          </ul>
        </div>

        <div v-if="result.failedConditions?.length" class="condition-list">
          <h6>未满足的条件</h6>
          <ul>
            <li v-for="(cond, i) in result.failedConditions" :key="i" class="failed">
              {{ cond }}
            </li>
          </ul>
        </div>

        <div v-if="result.actionResults?.length" class="action-list">
          <h6>动作执行结果</h6>
          <div v-for="(action, i) in result.actionResults" :key="i" class="action-item">
            <span class="action-type">{{ action.type }}</span>
            <span class="action-result" :class="{ success: action.executed, error: !action.executed }">
              {{ action.executed ? "已执行" : "失败" }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.test-panel {
  background: #fff;
  border: 1px solid #e9eee9;
  border-radius: 12px;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid #e9eee9;
  background: #fafdfc;
}

.panel-header h4 {
  margin: 0;
  font-size: 14px;
  color: #1f3e44;
}

.panel-header small {
  color: #7a8d89;
  font-size: 12px;
}

.close-btn {
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

.close-btn:hover {
  background: #f0f5f4;
}

.panel-body {
  padding: 18px;
}

.test-data h5,
.test-result h5 {
  margin: 0 0 12px;
  font-size: 13px;
  color: #37555a;
  font-weight: 600;
}

.data-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.data-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.data-item span {
  font-size: 11px;
  color: #7a8d89;
}

.input-with-unit {
  display: flex;
  align-items: center;
  gap: 4px;
}

.input-with-unit input {
  flex: 1;
  padding: 6px 8px;
  border: 1px solid #d9e6e1;
  border-radius: 4px;
  font-size: 13px;
  background: #fafdfc;
}

.input-with-unit .unit {
  font-size: 11px;
  color: #7a8d89;
  min-width: 24px;
}

.test-actions {
  margin: 16px 0;
  padding-top: 16px;
  border-top: 1px dashed #e9eee9;
}

.run-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  border: none;
  background: #2b6cb0;
  color: #fff;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.run-btn:hover:not(:disabled) {
  background: #2c5282;
}

.run-btn:disabled {
  opacity: 0.6;
}

.result-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: 8px;
  margin-bottom: 16px;
}

.result-summary.passed {
  background: #ecf8f2;
  color: #38a169;
}

.result-summary.failed {
  background: #fff5f5;
  color: #c7553b;
}

.result-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.5);
}

.result-summary.passed .result-icon {
  background: rgba(56, 161, 105, 0.2);
}

.result-summary.failed .result-icon {
  background: rgba(199, 85, 59, 0.2);
}

.result-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.result-text strong {
  font-size: 14px;
}

.result-text span {
  font-size: 12px;
  opacity: 0.8;
}

.condition-list,
.action-list {
  margin-bottom: 12px;
}

.condition-list h6,
.action-list h6 {
  margin: 0 0 8px;
  font-size: 12px;
  color: #56716e;
  font-weight: 600;
}

.condition-list ul {
  list-style: none;
  margin: 0;
  padding: 0;
}

.condition-list li {
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-family: monospace;
  margin-bottom: 4px;
}

.condition-list li.passed {
  background: #ecf8f2;
  color: #38a169;
}

.condition-list li.failed {
  background: #fff5f5;
  color: #c7553b;
}

.action-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  background: #fafdfc;
  border-radius: 6px;
  margin-bottom: 6px;
}

.action-type {
  font-size: 12px;
  color: #37555a;
}

.action-result {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
}

.action-result.success {
  background: #ecf8f2;
  color: #38a169;
}

.action-result.error {
  background: #fff5f5;
  color: #c7553b;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
