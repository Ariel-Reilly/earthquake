<script setup>
/**
 * 统一事件状态机可视化组件（StateMachineDiagram.vue）。
 *
 * 7 状态 × 10 触发器的有限状态机图形化展示：
 *   DETECTED → CONFIRMING → CONFIRMED → WARNING_ISSUED → EMERGENCY_ACTIVE → CLOSED
 *                                                       ↘ CLOSED (从 WARNING_ISSUED CLOSE)
 *   CONFIRMING → REJECTED
 *   CLOSED → WARNING_ISSUED (REOPEN)
 *
 * 数据契约：与 dashboardApi.getStateMachineGraph() 返回结构完全一致。
 *
 * 用法：
 *   <StateMachineDiagram
 *     :states="graph.states"
 *     :transitions="graph.transitions"
 *     :active-state="currentState"
 *     :counts="stats"
 *     @select-state="onStateClick"
 *   />
 */
import { computed } from "vue";
import {
  Archive,
  Bell,
  CheckCircle2,
  ClipboardCheck,
  Eye,
  Truck,
  XCircle,
} from "lucide-vue-next";

const props = defineProps({
  states: { type: Array, required: true },
  transitions: { type: Array, required: true },
  activeState: { type: String, default: "" },
  counts: { type: Object, default: () => ({}) },
  highlightedStates: { type: Array, default: () => [] },
});
const emit = defineEmits(["select-state"]);

const ICON_MAP = {
  Eye: Eye,
  ClipboardCheck: ClipboardCheck,
  CheckCircle2: CheckCircle2,
  Bell: Bell,
  Truck: Truck,
  Archive: Archive,
  XCircle: XCircle,
};

/**
 * 状态分组（前端展示用）：
 *   - 主链路：DETECTED → CONFIRMING → CONFIRMED → WARNING_ISSUED → EMERGENCY_ACTIVE → CLOSED
 *   - 分支：REJECTED（驳回），CLOSED（终态）
 *
 * 横排布局：7 个节点一排紧凑展示，重点突出状态机全貌。
 */
const orderedStates = computed(() => props.states);

function pickIcon(name) {
  return ICON_MAP[name] ?? Eye;
}

function nodeColor(state) {
  return state.color ?? "#6f9e7f";
}

function stateCount(stateId) {
  return Number(props.counts?.[stateId] ?? 0);
}

function isActive(stateId) {
  return props.activeState === stateId;
}

function isHighlighted(stateId) {
  return props.highlightedStates.includes(stateId);
}

function isTerminal(stateId) {
  return stateId === "CLOSED" || stateId === "REJECTED";
}

function onStateClick(stateId) {
  emit("select-state", stateId);
}

/**
 * 把转移箭头按 from/to 索引化，给 SVG 画箭头用。
 */
const transitionPairs = computed(() =>
  props.transitions.map((t) => ({
    fromId: t.from,
    toId: t.to,
    label: t.trigger,
  })),
);

/**
 * 计算节点的水平坐标（横排布局，每节点占 1/7 宽度）。
 * SVG 宽 900，高 110。
 */
const SVG_W = 900;
const SVG_H = 110;
const NODE_W = 90;
const NODE_H = 56;
const N = orderedStates.value.length;

function nodeX(i) {
  // 第 i 个节点中心 x
  const margin = (SVG_W - N * NODE_W) / (N + 1);
  return margin + i * (NODE_W + margin) + NODE_W / 2;
}
function nodeY() {
  return SVG_H / 2 - NODE_H / 2;
}

function nodeLeftEdge(i) {
  const margin = (SVG_W - N * NODE_W) / (N + 1);
  return margin + i * (NODE_W + margin);
}
function nodeCenterX(i) {
  return nodeLeftEdge(i) + NODE_W / 2;
}

/**
 * 为每个 transition 计算 SVG 路径（贝塞尔曲线，从 from 节点右边到 to 节点左边）。
 * 自环箭头：from === to 时画一个小圈。
 */
function arrowPath(t) {
  const fromIdx = orderedStates.value.findIndex((s) => s.id === t.fromId);
  const toIdx = orderedStates.value.findIndex((s) => s.id === t.toId);
  if (fromIdx === -1 || toIdx === -1) return null;
  const x1 = nodeLeftEdge(fromIdx) + NODE_W;
  const y1 = nodeY() + NODE_H / 2;
  const x2 = nodeLeftEdge(toIdx);
  const y2 = nodeY() + NODE_H / 2;
  if (t.fromId === t.toId) {
    const cx = x1;
    return {
      d: `M ${cx} ${y1 - 8} q 18 -18 0 -36`,
      reverse: false,
      labelX: cx + 22,
      labelY: y1 - 26,
    };
  }
  // 横排相邻时画直线 + 弯曲；非相邻画贝塞尔
  const isSameRow = Math.abs(toIdx - fromIdx) === 1;
  if (isSameRow) {
    const midX = (x1 + x2) / 2;
    return {
      d: `M ${x1} ${y1} L ${midX - 4} ${y1} q 4 0 4 4 L ${x2} ${y2 + 4} q 0 -4 4 -4`,
      reverse: toIdx < fromIdx,
      labelX: midX,
      labelY: y1 - 10,
    };
  }
  const cpx = (x1 + x2) / 2;
  return {
    d: `M ${x1} ${y1} C ${cpx} ${y1 - 30}, ${cpx} ${y2 - 30}, ${x2} ${y2}`,
    reverse: toIdx < fromIdx,
    labelX: cpx,
    labelY: Math.min(y1, y2) - 36,
  };
}

/**
 * 同一对节点之间可能有多条 transfer（如 CONFIRMING→CLOSED 和 DETECTED→CLOSED 都是 DISMISS），
 * 需要堆叠 label 防重叠。
 */
const transitionLabelSlots = computed(() => {
  const slots = [];
  const seen = new Map(); // key=fromId|toId, count
  for (const t of transitionPairs.value) {
    const key = `${t.fromId}|${t.toId}`;
    const idx = seen.get(key) ?? 0;
    seen.set(key, idx + 1);
    const path = arrowPath(t);
    if (!path) continue;
    slots.push({ ...t, ...path, stackIndex: idx });
  }
  return slots;
});

const SUMMARY = computed(() => {
  const total = orderedStates.value.reduce((acc, s) => acc + stateCount(s.id), 0);
  const active = orderedStates.value
    .filter((s) => !isTerminal(s.id))
    .reduce((acc, s) => acc + stateCount(s.id), 0);
  const terminal = total - active;
  return { total, active, terminal };
});
</script>

<template>
  <div class="state-machine-diagram">
    <div class="sm-header">
      <div class="sm-title">
        <strong>统一事件状态机</strong>
        <span class="sm-subtitle">7 状态 · 12 转移 · Spring StateMachine 驱动</span>
      </div>
      <div class="sm-summary">
        <span class="chip teal">活动 {{ SUMMARY.active }}</span>
        <span class="chip gray">终态 {{ SUMMARY.terminal }}</span>
      </div>
    </div>

    <div class="sm-canvas-wrap">
      <svg :viewBox="`0 0 ${SVG_W} ${SVG_H}`" preserveAspectRatio="xMidYMid meet" class="sm-svg">
        <!-- 转移箭头（先画，节点在上层覆盖） -->
        <defs>
          <marker id="sm-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" fill="#8a9c90" />
          </marker>
          <marker id="sm-arrow-active" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" fill="#6f9e7f" />
          </marker>
        </defs>

        <g v-for="(t, i) in transitionLabelSlots" :key="`arrow-${i}`" class="arrow-group">
          <path
            :d="t.d"
            :stroke="activeState && (t.fromId === activeState || t.toId === activeState) ? '#6f9e7f' : '#cbd5e0'"
            :stroke-width="activeState && (t.fromId === activeState || t.toId === activeState) ? 1.6 : 1"
            fill="none"
            :marker-end="activeState && (t.fromId === activeState || t.toId === activeState) ? 'url(#sm-arrow-active)' : 'url(#sm-arrow)'"
          />
          <text
            :x="t.labelX"
            :y="t.labelY - (t.stackIndex * 12)"
            text-anchor="middle"
            class="arrow-label"
            :class="{ active: activeState && (t.fromId === activeState || t.toId === activeState) }"
          >{{ t.label }}</text>
        </g>
      </svg>

      <!-- 节点层（绝对定位覆盖在 SVG 上方，方便响应点击 + 渲染文本） -->
      <div class="sm-nodes">
        <button
          v-for="(state, i) in orderedStates"
          :key="state.id"
          class="sm-node"
          :class="{
            active: isActive(state.id),
            highlighted: isHighlighted(state.id),
            terminal: isTerminal(state.id),
          }"
          :style="{
            left: `${nodeLeftEdge(i)}px`,
            top: `${nodeY()}px`,
            width: `${NODE_W}px`,
            height: `${NODE_H}px`,
            '--node-color': nodeColor(state.id),
          }"
          @click="onStateClick(state.id)"
        >
          <span class="node-icon">
            <component :is="pickIcon(state.icon)" :size="16" />
          </span>
          <span class="node-label">{{ state.label }}</span>
          <span class="node-count" v-if="stateCount(state.id) > 0">{{ stateCount(state.id) }}</span>
          <span class="node-code">{{ state.id }}</span>
        </button>
      </div>
    </div>

    <div class="sm-legend">
      <span><span class="dot" style="background:#6f9e7f"></span>主链路</span>
      <span><span class="dot" style="background:#e06060"></span>应急进行中</span>
      <span><span class="dot" style="background:#9aab9f"></span>终态（CLOSED/REJECTED）</span>
      <span class="hint">点击节点筛选该状态的事件</span>
    </div>
  </div>
</template>

<style scoped>
.state-machine-diagram {
  background: linear-gradient(180deg, color-mix(in srgb, var(--c-surface) 92%, transparent), color-mix(in srgb, var(--c-surface-2) 92%, transparent));
  border-radius: var(--r-lg);
  border: 1px solid var(--c-border);
  padding: 18px 20px 14px;
  box-shadow: 0 6px 20px rgba(46, 67, 93, 0.06);
}
.sm-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  gap: 12px;
  flex-wrap: wrap;
}
.sm-title strong {
  font-size: 14px;
  color: var(--c-ink-1);
  margin-right: 8px;
}
.sm-subtitle {
  font-size: 12px;
  color: var(--c-text-2);
}
.sm-summary {
  display: flex;
  gap: 6px;
}
.chip {
  font-size: 11px;
  padding: 3px 8px;
  border-radius: var(--r-pill);
  font-weight: 600;
}
.chip.teal { background: rgba(79, 143, 153, 0.12); color: var(--c-brand); }
.chip.gray { background: rgba(122, 141, 137, 0.14); color: var(--c-ink-2); }

.sm-canvas-wrap {
  position: relative;
  width: 100%;
  height: 130px;
  overflow-x: auto;
  overflow-y: hidden;
}
.sm-svg {
  width: 100%;
  min-width: 900px;
  height: 110px;
  position: absolute;
  top: 0;
  left: 0;
  z-index: 1;
}
.arrow-label {
  font-size: 9.5px;
  fill: var(--c-text-3);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-weight: 500;
}
.arrow-label.active { fill: var(--c-brand); font-weight: 700; }
.sm-nodes {
  position: relative;
  z-index: 2;
  min-width: 900px;
  height: 130px;
}
.sm-node {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  padding: 4px 6px;
  background: var(--c-surface);
  border: 1.5px solid var(--node-color, var(--c-brand));
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: all 0.18s ease;
  color: var(--node-color, var(--c-brand));
  box-shadow: 0 2px 6px rgba(46, 67, 93, 0.05);
}
.sm-node:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 14px rgba(46, 67, 93, 0.12);
}
.sm-node.active {
  background: var(--node-color, var(--c-brand));
  color: var(--c-on-brand);
  transform: translateY(-2px) scale(1.04);
  box-shadow: 0 8px 20px color-mix(in srgb, var(--c-brand) 22%, transparent)
    /* = rgba 111,158,127/.22 */;
  border-width: 2px;
}
.sm-node.highlighted:not(.terminal):not(.active) {
  background: rgba(255, 255, 255, 1);
  border-style: solid;
  border-width: 2px;
}
.sm-node.terminal:not(.active) {
  background: rgba(122, 141, 137, 0.08);
  border-style: dashed;
}
.node-icon {
  display: flex;
  align-items: center;
  justify-content: center;
}
.node-label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
}
.node-count {
  position: absolute;
  top: -8px;
  right: -6px;
  background: var(--c-danger);
  color: var(--c-on-brand);
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: var(--r-pill);
  min-width: 18px;
  text-align: center;
  box-shadow: 0 1px 4px rgba(224, 96, 96, 0.4);
}
.sm-node.active .node-count { background: var(--c-surface); color: var(--node-color, var(--c-brand)); }
.node-code {
  font-size: 8.5px;
  opacity: 0.65;
  font-family: ui-monospace, monospace;
  letter-spacing: 0.3px;
}
.sm-legend {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 11px;
  color: var(--c-ink-2);
  padding-top: 10px;
  border-top: 1px dashed var(--c-border);
  margin-top: 6px;
}
.sm-legend .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}
.sm-legend .hint {
  margin-left: auto;
  color: var(--c-text-3);
  font-style: italic;
}
</style>
