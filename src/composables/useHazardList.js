import { computed, ref, watch } from 'vue'
import { dashboardApi } from '../services/dashboardApi'

export function useHazardList(props) {
  const levelFilter = ref('全部')
  const selected = ref(null)
  const drawerOpen = ref(false)
  const hazards = ref([])
  const loading = ref(true)
  const paging = ref({ page: 1, size: 10, total: 0, pages: 0 })

  const normalizedSearch = computed(() => (props.searchTerm ?? '').trim().toLowerCase())
  const filteredHazards = computed(() => {
    const keyword = normalizedSearch.value
    return hazards.value.filter((item) => {
      const matchLevel = levelFilter.value === '全部' || item.level === levelFilter.value
      const matchKeyword = !keyword || [item.name, item.type, item.level, item.owner, item.status, item.hazardCode].some((value) => String(value).toLowerCase().includes(keyword))
      return matchLevel && matchKeyword
    })
  })

  async function loadData(page = 1) {
    loading.value = true
    try {
      const data = await dashboardApi.getHazards({
        page,
        size: paging.value.size,
        keyword: props.searchTerm,
        level: levelFilter.value === '全部' ? '' : levelFilter.value,
      })
      let list = data.list ?? []
      // AI 综合分已由后端 AiRiskEnricher 注入到 HazardDto.aiLevel/aiScore 字段；这里做兜底 + 排序
      // （如果后端某天没注入，前端自己从 /comprehensive-analysis/all 拉一次）
      const missingAi = list.some((h) => h.aiScore == null)
      if (missingAi) {
        try {
          const aiList = await dashboardApi.getAiComprehensiveAll()
          const map = new Map()
          aiList.forEach((s) => map.set(s.hazardCode, s))
          list = list.map((h) => {
            const ai = map.get(h.hazardCode ?? h.code)
            return {
              ...h,
              aiScore: h.aiScore ?? ai?.riskScore ?? null,
              aiLevel: h.aiLevel ?? ai?.riskLevel ?? null,
              aiAnalyzedAt: h.aiAnalyzedAt ?? ai?.analyzedAt ?? null,
            }
          })
        } catch (_) { /* 忽略，AI 不可用 */ }
      }
      // 按 AI 综合分排序（高 → 低），无 AI 分的排最后
      list.sort((a, b) => (b.aiScore ?? -1) - (a.aiScore ?? -1))
      hazards.value = list
      paging.value = {
        page: data.page ?? page,
        size: data.size ?? paging.value.size,
        total: data.total ?? hazards.value.length,
        pages: data.pages ?? 1,
      }
      selected.value = hazards.value[0] ?? null
      drawerOpen.value = Boolean(selected.value)
    } finally {
      loading.value = false
    }
  }

  function openDetail(point) {
    selected.value = point
    drawerOpen.value = true
  }

  function closeDrawer() {
    drawerOpen.value = false
  }

  function setLevelFilter(level) {
    levelFilter.value = level
  }

  watch(() => props.searchTerm, () => loadData(1))

  return {
    levelFilter,
    selected,
    drawerOpen,
    hazards,
    loading,
    paging,
    filteredHazards,
    loadData,
    openDetail,
    closeDrawer,
    setLevelFilter,
  }
}
