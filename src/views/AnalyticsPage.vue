<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Activity, AlertTriangle, CalendarDays, Database, Gauge, Radio, BarChart3, LineChart, PieChart, Map, RefreshCcw } from 'lucide-vue-next'
import { dashboardApi } from '../services/dashboardApi'

const router = useRouter()

const hazardPoints = ref([])
const overviewStats = ref([])
const charts = ref(null)
const loading = ref(true)

const riskBars = () => charts.value?.riskTrend ?? []
const monthlyBars = () => charts.value?.monthlyInspection ?? []
const warningTypes = () => charts.value?.warningType ?? []
const deviceTrend = () => charts.value?.deviceTrend ?? []
const heatmap = () => charts.value?.heatmap ?? []
const riskZones = () => charts.value?.riskZones ?? []

function chartBarWidth(value, max = 100) {
  return `${Math.max(8, Math.round((value / max) * 100))}%`
}

async function loadData() {
  loading.value = true
  const data = await dashboardApi.getAnalytics()
  hazardPoints.value = data.hazardPoints
  overviewStats.value = data.overviewStats
  charts.value = data.charts
  loading.value = false
}

onMounted(loadData)
</script>

<template>
  <div>
    <div v-if="loading" class="panel" style="padding: 24px;">统计分析数据加载中...</div>
    <template v-else>
      <div class="stats-grid">
        <div class="stat-card"><div class="stat-icon teal"><Activity :size="19" /></div><div class="stat-copy"><span>整体风险评分</span><strong>78 <small>/100</small></strong><em class="positive">中高风险 <i>需持续关注</i></em></div></div>
        <div class="stat-card"><div class="stat-icon blue"><CalendarDays :size="19" /></div><div class="stat-copy"><span>巡检完成率</span><strong>92.4 <small>%</small></strong><em class="positive">↑ 6.3% <i>较昨日</i></em></div></div>
        <div class="stat-card"><div class="stat-icon orange"><AlertTriangle :size="19" /></div><div class="stat-copy"><span>预警转化率</span><strong>61 <small>%</small></strong><em class="negative">仍需优化 <i>处置效率</i></em></div></div>
        <div class="stat-card"><div class="stat-icon purple"><Database :size="19" /></div><div class="stat-copy"><span>数据完整率</span><strong>99.1 <small>%</small></strong><em class="positive">上报稳定 <i>库表健康</i></em></div></div>
      </div>

      <div class="content-grid">
        <section class="panel warning-panel analytics-panel">
          <div class="panel-header compact">
            <div>
              <h2>风险趋势图</h2>
              <p>近 7 日风险评分变化</p>
            </div>
            <button class="text-btn" @click="loadData"><RefreshCcw :size="15" />刷新</button>
          </div>
          <div class="chart-card">
            <div class="mini-chart-header">
              <strong><LineChart :size="16" />风险趋势</strong>
              <span>稳定上升</span>
            </div>
            <div class="bar-chart">
              <div v-for="(value, index) in riskBars()" :key="index" class="bar-item">
                <i :style="{ height: `${value}%` }"></i>
                <span>第{{ index + 1 }}天</span>
              </div>
            </div>
          </div>

          <div class="chart-card">
            <div class="mini-chart-header">
              <strong><BarChart3 :size="16" />月度巡检趋势</strong>
              <span>12 个月完成情况</span>
            </div>
            <div class="line-rail">
              <div v-for="(value, index) in monthlyBars()" :key="index" class="line-node" :style="{ left: `${(index / Math.max(monthlyBars().length - 1, 1)) * 100}%`, bottom: `${value}%` }">
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
              <div v-for="item in warningTypes()" :key="item.label" class="pie-item">
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
              <div v-for="(value, index) in deviceTrend()" :key="index" class="bar-item">
                <i :style="{ height: `${value}%`, background: '#4f8f99' }"></i>
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
            <article v-for="item in heatmap()" :key="item.region" class="heatmap-card" :class="`heat-${item.risk}`">
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
              <article v-for="zone in riskZones()" :key="zone.name" class="risk-zone" :style="{ left: `${zone.x}%`, top: `${zone.y}%`, width: `${zone.width}%`, height: `${zone.height}%`, borderColor: zone.color, background: `${zone.color}18` }">
                <strong>{{ zone.name }}</strong>
                <span>{{ zone.count }} 个隐患点 · {{ zone.level }}风险</span>
                <i :style="{ background: zone.color }"></i>
              </article>
            </div>
          </div>
          <div class="warning-list">
            <article v-for="point in hazardPoints" :key="point.id" class="warning-item" @click="router.push(`/hazards/${point.id}`)">
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
  </div>
</template>
