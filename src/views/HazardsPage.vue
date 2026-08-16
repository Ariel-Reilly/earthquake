<script setup>
import { onMounted } from 'vue'
import { Database, Gauge, Layers3, MapPin, Mountain, Search, X } from 'lucide-vue-next'
import { useHazardList } from '../composables/useHazardList'

const props = defineProps({
  searchTerm: { type: String, default: '' },
})

defineEmits(['notify', 'go-to'])

const {
  levelFilter,
  selected,
  drawerOpen,
  loading,
  filteredHazards,
  paging,
  loadData,
  openDetail,
  closeDrawer,
  setLevelFilter,
} = useHazardList(props)

onMounted(loadData)
</script>

<template>
  <div class="content-grid">
    <section class="panel warning-panel">
      <div class="panel-header compact">
        <div>
          <h2>灾害点列表</h2>
          <p>支持搜索、筛选和快速查看</p>
        </div>
        <button class="text-btn" @click="$emit('notify', '已同步灾害点数据')"><Database :size="15" />同步数据</button>
      </div>
      <div v-if="loading" style="padding: 0 20px 18px; color: #7a8d89;">正在加载灾害点数据...</div>
      <template v-else>
        <div class="map-toolbar">
          <div class="map-legend">
            <button v-for="item in ['全部', '高风险', '关注', '正常']" :key="item" :class="{ selected: levelFilter === item }" @click="setLevelFilter(item); loadData(1)">
              <span :class="item === '全部' ? 'all-dot' : item === '高风险' ? 'danger-dot' : item === '关注' ? 'warn-dot' : 'safe-dot'"></span>{{ item }}
            </button>
          </div>
          <div class="map-tools">
            <button @click="$emit('notify', '已打开空间搜索')"><Search :size="15" />搜索</button>
            <button @click="$emit('notify', '已切换地形图层')"><Layers3 :size="15" />图层</button>
          </div>
        </div>
        <div v-if="filteredHazards.length" class="warning-list">
          <article v-for="point in filteredHazards" :key="point.id" class="warning-item" @click="openDetail(point)">
            <div class="warning-symbol" :class="point.level === '高风险' ? 'orange' : point.level === '关注' ? 'yellow' : 'blue'">{{ point.level === '高风险' ? '!' : point.level === '关注' ? '?' : '✓' }}</div>
            <div class="warning-body">
              <div class="warning-title"><strong>{{ point.name }}</strong><span :class="point.level === '高风险' ? 'orange' : point.level === '关注' ? 'yellow' : 'blue'">{{ point.level }}</span></div>
              <p>{{ point.type }} · {{ point.owner }} · {{ point.status }}</p>
              <small><MapPin :size="12" />{{ point.distance }} · {{ point.lon }}, {{ point.lat }}</small>
            </div>
          </article>
        </div>
        <div v-else class="panel-empty" style="padding: 0 20px 18px; color: #7a8d89;">当前条件下没有匹配的灾害点</div>
        <div class="panel-footer pagination-bar">
          <span>共 {{ paging.total }} 条，{{ paging.page }}/{{ paging.pages || 1 }} 页</span>
          <div class="detail-utility">
            <button class="secondary-btn" :disabled="paging.page <= 1" @click="loadData(paging.page - 1)">上一页</button>
            <button class="secondary-btn" :disabled="paging.page >= (paging.pages || 1)" @click="loadData(paging.page + 1)">下一页</button>
          </div>
        </div>
      </template>
    </section>

    <section class="panel system-panel">
      <div class="panel-header compact">
        <div>
          <h2>灾害点详情</h2>
          <p>档案、风险和空间信息</p>
        </div>
        <span class="health-label"><i></i>{{ selected?.level || '未选择' }}</span>
      </div>
      <template v-if="selected">
        <div class="system-list">
          <div><span><Mountain :size="16" />点位名称</span><strong>{{ selected.name }}</strong></div>
          <div><span><Layers3 :size="16" />灾害类型</span><strong>{{ selected.type }}</strong></div>
          <div><span><MapPin :size="16" />所属辖区</span><strong>{{ selected.owner }}</strong></div>
          <div><span><Gauge :size="16" />当前状态</span><strong>{{ selected.status }}</strong></div>
        </div>
        <div class="task-summary">
          <div class="progress-ring"><span>{{ selected.level === '高风险' ? '86' : selected.level === '关注' ? '63' : '41' }}<small>%</small></span></div>
          <div class="task-overview">
            <strong>{{ selected.distance }}</strong>
            <span>与值守中心距离</span>
            <div class="task-bars"><i style="width: 72%"></i></div>
            <p>最近巡检时间：今天 09:30</p>
          </div>
        </div>
        <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
          <button class="secondary-btn" @click="$emit('notify', '已生成灾害点巡检记录')">生成记录</button>
          <button class="primary-btn" @click="$emit('go-to', '/tasks')">创建任务</button>
        </div>
      </template>
      <div v-else class="panel-empty" style="padding: 0 20px 18px; color: #7a8d89;">暂无灾害点数据</div>
    </section>
  </div>

  <div v-if="drawerOpen && selected" class="modal-backdrop" @click.self="closeDrawer">
    <div class="modal">
      <div class="modal-head">
        <div><span class="eyebrow">HAZARD POINT</span><h2>{{ selected.name }}</h2></div>
        <button @click="closeDrawer"><X :size="18" /></button>
      </div>
      <label>空间坐标<input :value="`${selected.lon}, ${selected.lat}`" readonly /></label>
      <label>当前状态<input :value="selected.status" readonly /></label>
      <label>风险等级<input :value="selected.level" readonly /></label>
      <div class="modal-foot">
        <button class="secondary-btn" @click="closeDrawer">关闭</button>
        <button class="primary-btn" @click="$emit('notify', `已打开 ${selected.name} 详情`)">查看完整档案</button>
      </div>
    </div>
  </div>
</template>
