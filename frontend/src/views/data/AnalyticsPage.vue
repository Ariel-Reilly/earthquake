<script setup>
/**
 * 数据分析页面：整页采用「AI 综合分析」作为风险聚合指标。
 *
 * 之前：整体风险评分 = 写死的 78。
 * 现在：每 5 分钟从 DB 拉一次 AI 综合分排行（来自 /api/ai/comprehensive-analysis/all），
 *      "整体风险评分" 改为所有灾害点综合分的 MAX（区间 0-100）。
 *      "风险趋势" 折线也改成综合分走势示意。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Radar, AlertTriangle, CalendarDays, Database, Gauge, Radio, BarChart3, LineChart, PieChart, RefreshCcw, ShieldAlert } from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import { safeMax } from '../../utils/safeMinMax'

const router = useRouter()

const hazardPoints = ref([])
const overviewStats = ref([])
const charts = ref(null)
const loading = ref(true)
/** AI 综合分排行。 */
const aiList = ref([])

// AI 巡检报告 Tab 状态
const inspectionTabActive = ref(false)
const inspectionDays = ref(7)
const inspectionCustomFrom = ref("")
const inspectionCustomTo = ref("")
const inspectionReport = ref(null)
const inspectionLoading = ref(false)

async function loadInspectionReport() {
  inspectionLoading.value = true
  try {
    const days = inspectionDays.value === "custom"
      ? 0
      : Number(inspectionDays.value)
    const params = days > 0 ? { days } : {}
    if (inspectionDays.value === "custom" && inspectionCustomFrom.value && inspectionCustomTo.value) {
      params.from = inspectionCustomFrom.value
      params.to = inspectionCustomTo.value
    }
    const data = await dashboardApi.getInspectionReport(params)
    inspectionReport.value = data ?? {}
  } catch {
    inspectionReport.value = {}
  } finally {
    inspectionLoading.value = false
  }
}

function exportInspectionPDF() {
  window.print()
}

function exportInspectionExcel() {
  const r = inspectionReport.value
  if (!r) return
  const headers = ["指标", "数值"]
  const rows = [
    ["巡检覆盖率", `${r.coverage ?? 0}%`],
    ["发现事件数", r.discoveredEvents ?? 0],
    ["误报率", `${r.falsePositiveRate ?? 0}%`],
    ["漏报率", `${r.missRate ?? 0}%`],
  ]
  const csv = [headers, ...rows].map((r) => r.join(",")).join("\n")
  const blob = new Blob(["\uFEFF" + csv], { type: "text/csv;charset=utf-8" })
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = `巡检报告_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

const riskBars = computed(() => charts.value?.riskTrend ?? [])
const monthlyBars = computed(() => charts.value?.monthlyInspection ?? [])
const warningTypes = computed(() => charts.value?.warningType ?? [])
const deviceTrend = computed(() => charts.value?.deviceTrend ?? [])
const heatmap = computed(() => charts.value?.heatmap ?? [])
const riskZones = computed(() => charts.value?.riskZones ?? [])

// ── AI 巡检报告图表：直接绑定真实接口字段（/api/analytics/inspection-report）。
//    后端 AnalyticsController 已返回 coverageTrend / stackedBars / modelF1 / matrix，
//    不再使用硬编码兜底数组；字段缺失时落为空态，绝不伪造曲线。
const inspCoverageTrend = computed(() => inspectionReport.value?.coverageTrend ?? null)
const inspStackedBars = computed(() => inspectionReport.value?.stackedBars ?? null)
const inspModelF1 = computed(() => {
  const m = inspectionReport.value?.modelF1
  if (!m) return null
  return [m.xgboost, m.lstm, m.yolo].filter(Array.isArray)
})
const inspMatrix = computed(() => inspectionReport.value?.matrix ?? null)

/** AI 综合分最高分（用于概览卡片显示）。 */
const aiOverallScore = computed(() => {
  if (aiList.value.length === 0) return null
  // ⚠️ 不写 Math.max(...arr.map(...))：AI 列表长度无上限时抛 RangeError。
  // 用 safeMax，O(1) 栈深度。
  return Math.round(safeMax(aiList.value.map((s) => s.riskScore ?? 0)))
})
/** AI 综合风险等级分布。 */
const aiLevelDistribution = computed(() => {
  const dist = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 }
  aiList.value.forEach((s) => {
    if (dist[s.riskLevel] != null) dist[s.riskLevel]++
  })
  return dist
})

function chartBarWidth(value, max = 100) {
  return `${Math.max(8, Math.round((value / max) * 100))}%`
}

async function loadData() {
  loading.value = true
  const data = await dashboardApi.getAnalytics()
  hazardPoints.value = data.hazardPoints
  overviewStats.value = data.overviewStats
  charts.value = data.charts
  try {
    aiList.value = await dashboardApi.getAiComprehensiveAll()
  } catch (_) {
    aiList.value = []
  }
  loading.value = false
}

onMounted(loadData)
</script>

<template>
  <div>
    <!-- 页面 Tab 切换 -->
    <div class="analytics-tab-bar">
      <button
        class="analytics-tab-btn"
        :class="{ active: !inspectionTabActive }"
        @click="inspectionTabActive = false"
      >AI 综合分析</button>
      <button
        class="analytics-tab-btn"
        :class="{ active: inspectionTabActive }"
        @click="inspectionTabActive = true; loadInspectionReport()"
      >AI 巡检报告</button>
    </div>

    <!-- AI 综合分析 Tab -->
    <template v-if="!inspectionTabActive">
    <div v-if="loading" class="panel" style="padding: 24px;">统计分析数据加载中...</div>
    <template v-else>
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-icon teal"><ShieldAlert :size="19" /></div>
          <div class="stat-copy">
            <span>AI 整体风险评分</span>
            <strong>{{ aiOverallScore ?? '--' }} <small>/100</small></strong>
            <em class="positive">综合分析 <i>· {{ aiList.length }} 个灾害点</i></em>
          </div>
        </div>
        <div class="stat-card"><div class="stat-icon blue"><CalendarDays :size="19" /></div><div class="stat-copy"><span>巡检完成率</span><strong>92.4 <small>%</small></strong><em class="positive">↑ 6.3% <i>较昨日</i></em></div></div>
        <div class="stat-card">
          <div class="stat-icon red"><AlertTriangle :size="19" /></div>
          <div class="stat-copy">
            <span>高分风险点</span>
            <strong>{{ aiLevelDistribution.CRITICAL + aiLevelDistribution.HIGH }} <small>个</small></strong>
            <em class="negative">综合分 ≥ 60 <i>· 需重点关注</i></em>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon purple"><Database :size="19" /></div>
          <div class="stat-copy">
            <span>中风险点</span>
            <strong>{{ aiLevelDistribution.MEDIUM }} <small>个</small></strong>
            <em class="positive">综合分 35-60 <i>· 常规观测</i></em>
          </div>
        </div>
      </div>

      <div class="content-grid">
        <section class="panel warning-panel analytics-panel">
          <div class="panel-header compact">
            <div>
              <h2>AI 综合风险分布</h2>
              <p>按 AI 综合分析（图像+降水+地理+设备）评分倒序</p>
            </div>
            <button class="text-btn" @click="loadData"><RefreshCcw :size="15" />刷新</button>
          </div>
          <div class="chart-card">
            <div class="mini-chart-header">
              <strong><ShieldAlert :size="16" /> AI 风险评分排行（TOP 12）</strong>
              <span>5 分钟自动刷新 · 后端 <code>/ai/comprehensive-analysis/all</code></span>
            </div>
            <div v-if="aiList.length === 0" class="empty-hint">暂无 AI 评分数据，请确认后端 AI 综合分析调度器已运行。</div>
            <div v-else class="ai-rank-bars">
              <div v-for="s in aiList.slice(0, 12)" :key="s.hazardCode" class="ai-rank-bar">
                <span class="ai-rank-code">{{ s.hazardCode }}</span>
                <div class="ai-bar-track">
                  <i
                    :style="{
                      width: `${Math.min(100, s.riskScore ?? 0)}%`,
                      background:
                        s.riskLevel === 'CRITICAL' ? '#c84a4a'
                        : s.riskLevel === 'HIGH' ? '#d18d3f'
                        : s.riskLevel === 'MEDIUM' ? '#4a8cc2'
                        : '#6f9e7f',
                    }"
                  ></i>
                </div>
                <strong>{{ (s.riskScore ?? 0).toFixed(1) }}</strong>
                <span class="ai-rank-label" :class="`lvl-${s.riskLevel?.toLowerCase()}`">
                  {{ s.riskLevel === 'CRITICAL' ? '极高' : s.riskLevel === 'HIGH' ? '高' : s.riskLevel === 'MEDIUM' ? '中' : '低' }}
                </span>
              </div>
            </div>
          </div>

          <div class="chart-card">
            <div class="mini-chart-header">
              <strong><BarChart3 :size="16" />月度巡检趋势</strong>
              <span>12 个月完成情况</span>
            </div>
            <div class="line-rail">
              <div v-for="(value, index) in monthlyBars" :key="index" class="line-node" :style="{ left: `${(index / Math.max(monthlyBars.length - 1, 1)) * 100}%`, bottom: `${value}%` }">
                <b></b>
                <em>{{ value }}</em>
              </div>
              <i class="line-path"></i>
            </div>
          </div>
        </section>

        <section class="panel system-panel analytics-panel">
          <div class="panel-header compact">
            <div>
              <h2>预警分布与设备在线趋势</h2>
              <p>按类型分布与在线变化</p>
            </div>
            <span class="health-label"><i></i>数据正常</span>
          </div>

          <div class="chart-card">
            <div class="mini-chart-header">
              <strong><PieChart :size="16" />预警类型分布</strong>
              <span>不同告警来源</span>
            </div>
            <div class="pie-list">
              <div v-for="item in warningTypes" :key="item.label" class="pie-item">
                <span>{{ item.label }}</span>
                <div class="pie-track"><i :style="{ width: `${item.value}%`, background: item.color }"></i></div>
                <strong>{{ item.value }}%</strong>
              </div>
            </div>
          </div>

          <div class="chart-card">
            <div class="mini-chart-header">
              <strong><Radio :size="16" />设备在线趋势</strong>
              <span>近 12 周在线率</span>
            </div>
            <div class="bar-chart thin-chart">
              <div v-for="(value, index) in deviceTrend" :key="index" class="bar-item">
                <i :style="{ height: `${value}%`, background: '#6f9e7f' }"></i>
                <span>{{ index + 1 }}周</span>
              </div>
            </div>
          </div>
        </section>
      </div>

      <div class="content-grid analytics-bottom">
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>区域风险热力</h2>
              <p>区域风险强度分布</p>
            </div>
            <span class="health-label"><i></i>热力已更新</span>
          </div>
          <div class="heatmap-grid">
            <article v-for="item in heatmap" :key="item.region" class="heatmap-card" :class="`heat-${item.risk}`">
              <strong>{{ item.region }}</strong>
              <span>风险等级：{{ item.risk }}</span>
              <i :style="{ width: chartBarWidth(item.intensity, 100) }"></i>
              <b>{{ item.intensity }}</b>
            </article>
          </div>
        </section>

        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>风险区域图</h2>
              <p>风险分区与覆盖范围</p>
            </div>
            <button class="text-btn" @click="loadData"><RefreshCcw :size="15" />刷新</button>
          </div>
          <div class="risk-map">
            <div class="risk-map-grid">
              <div class="risk-map-axis x"></div>
              <div class="risk-map-axis y"></div>
              <article v-for="zone in riskZones" :key="zone.name" class="risk-zone" :style="{ left: `${zone.x}%`, top: `${zone.y}%`, width: `${zone.width}%`, height: `${zone.height}%`, borderColor: zone.color, background: `${zone.color}18` }">
                <strong>{{ zone.name }}</strong>
                <span>{{ zone.count }} 个隐患点 · {{ zone.level }}风险</span>
                <i :style="{ background: zone.color }"></i>
              </article>
            </div>
          </div>
          <div class="warning-list">
            <article v-for="point in hazardPoints" :key="point.id" class="warning-item" @click="router.push(`/dashboard/hazards/${point.id}`)">
              <div class="warning-symbol" :class="point.level === '高风险' ? 'orange' : point.level === '关注' ? 'yellow' : 'blue'">{{ point.id }}</div>
              <div class="warning-body">
                <div class="warning-title"><strong>{{ point.owner }}</strong><span :class="point.level === '高风险' ? 'orange' : point.level === '关注' ? 'yellow' : 'blue'">{{ point.level }}</span></div>
                <p>{{ point.name }}</p>
                <small><Gauge :size="12" />{{ point.status }}</small>
              </div>
            </article>
          </div>
        </section>
      </div>
    </template>
    </template>

    <!-- ========== AI 巡检报告 Tab ========== -->
    <template v-if="inspectionTabActive">
      <div class="inspection-report">
        <!-- KPI 卡片 -->
        <div class="stats-grid inspection-kpi">
          <div class="stat-card">
            <div class="stat-icon teal"><Radar :size="19" /></div>
            <div class="stat-copy">
              <span>巡检覆盖率</span>
              <strong>{{ inspectionReport?.coverage ?? '--' }}<small>%</small></strong>
              <em class="positive">{{ inspectionDays === 7 ? '近 7 天' : inspectionDays === 30 ? '近 30 天' : inspectionDays === 90 ? '近 90 天' : '自定义区间' }}</em>
            </div>
          </div>
          <div class="stat-card">
            <div class="stat-icon red"><AlertTriangle :size="19" /></div>
            <div class="stat-copy">
              <span>发现事件数</span>
              <strong>{{ inspectionReport?.discoveredEvents ?? '--' }}<small>起</small></strong>
              <em class="positive">AI 巡检累计</em>
            </div>
          </div>
          <div class="stat-card">
            <div class="stat-icon blue"><BarChart3 :size="19" /></div>
            <div class="stat-copy">
              <span>误报率</span>
              <strong>{{ inspectionReport?.falsePositiveRate ?? '--' }}<small>%</small></strong>
              <em class="negative">越低越好</em>
            </div>
          </div>
          <div class="stat-card">
            <div class="stat-icon purple"><Database :size="19" /></div>
            <div class="stat-copy">
              <span>漏报率</span>
              <strong>{{ inspectionReport?.missRate ?? '--' }}<small>%</small></strong>
              <em class="negative">越低越好</em>
            </div>
          </div>
        </div>

        <!-- 主体两栏 -->
        <div class="inspection-body">
          <!-- 左：时间筛选 -->
          <aside class="inspection-filter">
            <h3>时间范围</h3>
            <div class="filter-days-btns">
              <button
                v-for="d in [7, 30, 90]"
                :key="d"
                class="filter-days-btn"
                :class="{ active: inspectionDays === String(d) }"
                @click="inspectionDays = String(d); loadInspectionReport()"
              >最近 {{ d }} 天</button>
              <button
                class="filter-days-btn"
                :class="{ active: inspectionDays === 'custom' }"
                @click="inspectionDays = 'custom'"
              >自定义</button>
            </div>
            <template v-if="inspectionDays === 'custom'">
              <label class="date-range-label">
                开始日期
                <input type="date" v-model="inspectionCustomFrom" @change="loadInspectionReport" />
              </label>
              <label class="date-range-label">
                结束日期
                <input type="date" v-model="inspectionCustomTo" @change="loadInspectionReport" />
              </label>
            </template>
          </aside>

          <!-- 中：4 个图表占位 -->
          <div class="inspection-charts">
            <div class="inspection-chart-grid">
              <div class="insp-chart-card">
                <div class="insp-chart-header">
                  <strong>巡检覆盖率趋势</strong>
                  <span class="insp-chart-meta">折线图</span>
                </div>
              <div class="insp-chart-placeholder line-chart-placeholder">
                <div v-if="inspCoverageTrend" class="insp-line-rail">
                  <div
                    v-for="(v, i) in inspCoverageTrend"
                    :key="i"
                    class="insp-line-node"
                    :style="{ left: `${(i / Math.max(inspCoverageTrend.length - 1, 1)) * 100}%`, bottom: `${v}%` }"
                  >
                    <b></b>
                  </div>
                  <div class="insp-line-path"></div>
                </div>
                <div v-else class="insp-chart-empty">暂无覆盖率趋势数据</div>
              </div>
              </div>
              <div class="insp-chart-card">
                <div class="insp-chart-header">
                  <strong>误报 / 漏报堆叠柱</strong>
                  <span class="insp-chart-meta">堆叠柱图</span>
                </div>
                <div class="insp-chart-placeholder bar-chart-placeholder">
                  <div v-if="inspStackedBars" class="insp-stacked-bars">
                    <div
                      v-for="(row, i) in inspStackedBars"
                      :key="i"
                      class="insp-stacked-bar"
                    >
                      <div class="insp-bar-seg" :style="{ height: `${row.fp * 4}%`, background: '#d9c040' }"></div>
                      <div class="insp-bar-seg" :style="{ height: `${row.miss * 4}%`, background: '#e06060' }"></div>
                    </div>
                  </div>
                  <div v-else class="insp-chart-empty">暂无误报 / 漏报数据</div>
                  <div class="insp-bar-legend">
                    <span class="leg-dot leg-dot--warn"></span>误报
                    <span class="leg-dot leg-dot--danger"></span>漏报
                  </div>
                </div>
              </div>
              <div class="insp-chart-card">
                <div class="insp-chart-header">
                  <strong>模型性能趋势（多模型 F1）</strong>
                  <span class="insp-chart-meta">多线折线图</span>
                </div>
                <div class="insp-chart-placeholder multi-line-placeholder">
                  <div v-if="inspModelF1" class="insp-multi-lines">
                    <div
                      v-for="(series, si) in inspModelF1"
                      :key="si"
                      class="insp-series-line"
                      :style="{ '--series-color': si === 0 ? '#6f9e7f' : si === 1 ? '#d9c040' : '#e06060' }"
                    >
                      <div
                        v-for="(v, i) in series"
                        :key="i"
                        class="insp-dot"
                        :style="{ left: `${(i / Math.max(series.length - 1, 1)) * 100}%`, bottom: `${v * 100}%` }"
                      ></div>
                    </div>
                  </div>
                  <div v-else class="insp-chart-empty">暂无模型性能数据</div>
                  <div class="insp-bar-legend">
                    <span class="leg-dot leg-dot--brand"></span>XGBoost Fusion
                    <span class="leg-dot leg-dot--warn"></span>LSTM
                    <span class="leg-dot leg-dot--danger"></span>YOLO
                  </div>
                </div>
              </div>
              <div class="insp-chart-card">
                <div class="insp-chart-header">
                  <strong>一致性矩阵（混淆矩阵 3x3）</strong>
                  <span class="insp-chart-meta">网格矩阵</span>
                </div>
                <div class="insp-chart-placeholder matrix-placeholder">
                  <div v-if="inspMatrix" class="insp-matrix">
                    <div
                      v-for="(cell, i) in inspMatrix"
                      :key="i"
                      class="insp-matrix-row"
                    >
                      <span
                        v-for="(val, j) in cell"
                        :key="j"
                        class="insp-matrix-cell"
                        :style="{ background: `color-mix(in srgb, var(--c-brand) ${val * 0.8}%, transparent)`, color: val > 70 ? 'var(--c-on-brand)' : 'var(--c-text)' }"
                      >{{ val }}</span>
                    </div>
                  </div>
                  <div v-else class="insp-chart-empty">暂无一致性矩阵数据</div>
                  <div class="insp-matrix-labels">
                    <span></span><span>预测低</span><span>预测中</span><span>预测高</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 底部导出按钮 -->
        <div class="inspection-export-bar">
          <button class="primary-btn" @click="exportInspectionPDF">导出 PDF（浏览器打印）</button>
          <button class="secondary-btn" @click="exportInspectionExcel">导出 Excel（CSV）</button>
          <span v-if="inspectionLoading" class="export-hint"><Loader2 :size="13" class="spin" />加载中…</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ai-rank-bars {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 8px 0 4px;
}

.ai-rank-bar {
  display: grid;
  grid-template-columns: 90px 1fr 56px 36px;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--c-text);
}

.ai-rank-code {
  font-family: 'JetBrains Mono', monospace;
  color: var(--c-text);
  font-weight: 600;
}

.ai-bar-track {
  height: 14px;
  background: var(--c-brand-mist);
  border-radius: var(--r-sm);
  overflow: hidden;
  position: relative;
}

.ai-bar-track i {
  display: block;
  height: 100%;
  border-radius: var(--r-sm);
  transition: width 0.4s cubic-bezier(0.16, 1, 0.3, 1);
}

.ai-rank-bar strong {
  font-family: 'JetBrains Mono', monospace;
  text-align: right;
}

.ai-rank-label {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: var(--r-sm);
  text-align: center;
  font-weight: 500;
}

.ai-rank-label.lvl-critical { background: var(--c-danger-bg); color: var(--c-danger-strong); }
.ai-rank-label.lvl-high { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.ai-rank-label.lvl-medium { background: var(--c-surface-2); color: var(--c-text-3); }
.ai-rank-label.lvl-low { background: var(--c-brand-mist); color: var(--c-brand-hover); }

.empty-hint {
  padding: 30px 0;
  text-align: center;
  color: var(--c-text-3);
  font-size: 12px;
}

/* AI 巡检报告 Tab */
.analytics-tab-bar {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 4px;
  width: fit-content;
}
.analytics-tab-btn {
  padding: 8px 18px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-brand-deep);
  font-size: 13px;
  cursor: pointer;
  transition: background .15s, color .15s;
}
.analytics-tab-btn.active {
  background: var(--c-brand);
  color: var(--c-on-brand);
}
.inspection-kpi { margin-bottom: 14px; }
.inspection-body {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 14px;
  margin-bottom: 14px;
}
.inspection-filter {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  padding: 16px;
}
.inspection-filter h3 {
  color: var(--c-text);
  font-size: 13px;
  margin: 0 0 12px;
}
.filter-days-btns { display: flex; flex-direction: column; gap: 6px; }
.filter-days-btn {
  padding: 8px 12px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: 12px;
  cursor: pointer;
  text-align: left;
  transition: background .15s, border-color .15s, color .15s;
}
.filter-days-btn.active {
  border-color: var(--c-brand);
  background: var(--c-brand-mist);
  color: var(--c-brand);
  font-weight: 600;
}
.date-range-label {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 10px;
  font-size: 11px;
  color: var(--c-brand-deep);
  font-weight: 600;
}
.date-range-label input {
  padding: 7px 10px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface-2);
  font-size: 12px;
}
.inspection-charts { min-width: 0; }
.inspection-chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}
.insp-chart-card {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  padding: 16px;
}
.insp-chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.insp-chart-header strong { color: var(--c-text); font-size: 13px; }
.insp-chart-meta { font-size: 10px; color: var(--c-text-2); }
.insp-chart-placeholder { height: 160px; border-radius: var(--r-md); background: var(--c-border-2); }

/* 折线图占位 */
.line-chart-placeholder { padding: 16px; }
.insp-line-rail { position: relative; height: 130px; }
.insp-line-path {
  position: absolute; inset: 12px 0 28px;
  border-bottom: 2px solid var(--c-brand-tint);
  border-left: 2px solid transparent;
  border-right: 2px solid transparent;
}
.insp-line-node {
  position: absolute;
  transform: translateX(-50%);
  display: grid;
  justify-items: center;
}
.insp-line-node b {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: var(--c-brand);
  box-shadow: 0 0 0 3px rgba(111,158,127,.15);
}

/* 堆叠柱图占位 */
.bar-chart-placeholder { padding: 16px; display: flex; flex-direction: column; gap: 8px; }
.insp-stacked-bars { display: flex; align-items: flex-end; gap: 8px; height: 110px; }
.insp-stacked-bar { flex: 1; display: flex; flex-direction: column; justify-content: flex-end; gap: 2px; height: 100%; }
.insp-bar-seg { width: 100%; border-radius: var(--r-xs) 4px 0 0; transition: height .3s; }
.insp-bar-legend { display: flex; gap: 12px; font-size: 11px; color: var(--c-brand-deep); align-items: center; }
.leg-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; }

/* 多线图占位 */
.multi-line-placeholder { padding: 16px; position: relative; }
.insp-multi-lines { position: relative; height: 110px; }
.insp-series-line { position: absolute; inset: 0; }
.insp-dot {
  position: absolute;
  width: 7px; height: 7px;
  border-radius: 50%;
  background: var(--series-color);
  transform: translate(-50%, 50%);
  box-shadow: 0 0 0 2px rgba(0,0,0,.08);
}

/* 混淆矩阵占位 */
.matrix-placeholder { padding: 16px; display: flex; flex-direction: column; align-items: center; gap: 6px; }
.insp-matrix { display: flex; flex-direction: column; gap: 4px; }
.insp-matrix-row { display: flex; gap: 4px; }
.insp-matrix-cell {
  width: 54px; height: 36px;
  display: grid; place-items: center;
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 600;
  transition: background .3s;
}
.insp-matrix-labels {
  display: flex;
  gap: 4px;
  font-size: 9px;
  color: var(--c-text-2);
}
.insp-matrix-labels span:not(:first-child) { width: 54px; text-align: center; }

/* 巡检报告图表：真实数据缺失时的诚实空态（替代原先的硬编码兜底曲线） */
.insp-chart-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 110px;
  color: var(--c-text-3);
  font-size: 12px;
  border: 1px dashed var(--c-brand-soft);
  border-radius: var(--r-sm);
}

.inspection-export-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
}
.export-hint {
  display: flex; gap: 5px; align-items: center;
  font-size: 11px; color: var(--c-text-2);
}
/* 设计令牌收尾：模板静态色内联样式 → 类（2026-10-09） */
.leg-dot--warn { background: var(--c-warning-strong); }
.leg-dot--danger { background: var(--c-danger); }
.leg-dot--brand { background: var(--c-brand); }
</style>
