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
      const matchKeyword = !keyword || [item.name, item.type, item.level, item.owner, item.status].some((value) => String(value).toLowerCase().includes(keyword))
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
      hazards.value = data.list ?? []
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
