/**
 * 总览页-地图地址/坐标搜索 composable。
 *
 * 把搜索面板的状态机、防抖、AbortController、选点-缩放、反向地理补全
 * 这套原本散落在 OverviewPage.vue 第 178-1102 行 ~130 行的逻辑收敛到这里,
 * 让 OverviewPage.vue 只剩 `<MapSearchBox v-bind="search.bind" />` 一行接入。
 *
 * 状态机:
 *   idle    输入框为空、没展开候选项
 *   typing  用户正在输入,未发起请求(防抖 280ms)
 *   loading 已发起搜索,等待返回
 *   results 拿到候选,渲染下拉
 *   empty   搜索无结果
 */

import { ref } from 'vue'
import { geocodeAddress, reverseGeocode, geocoderProvider } from '../services/_architecture/core/geocodeService.js'

const DEBOUNCE_MS = 280

export function useOverviewSearch({ mapController, emit }) {
  const searchTerm = ref('')
  const searchPanelOpen = ref(false)
  const searchPanelState = ref('idle')
  const searchResults = ref([])
  const searchError = ref('')
  const searchMarker = ref(null)
  const geocoderKind = ref(geocoderProvider())

  let searchDebounceTimer = null
  let searchAbortController = null

  function cancelInflight() {
    if (searchAbortController) {
      searchAbortController.abort()
      searchAbortController = null
    }
  }

  function clearTimer() {
    if (searchDebounceTimer) {
      clearTimeout(searchDebounceTimer)
      searchDebounceTimer = null
    }
  }

  function resetPanel() {
    searchPanelState.value = 'idle'
    searchResults.value = []
    searchError.value = ''
  }

  /**
   * 输入框变化: 防抖 280ms 触发搜索。空文本清空候选项。
   */
  function onSearchInput() {
    clearTimer()
    const term = searchTerm.value.trim()
    if (!term) {
      resetPanel()
      searchPanelOpen.value = false
      cancelInflight()
      return
    }
    searchPanelOpen.value = true
    searchPanelState.value = 'typing'
    searchDebounceTimer = setTimeout(() => {
      runSearch(term)
    }, DEBOUNCE_MS)
  }

  /** 主动跑一次搜索(用户按 Enter 或点击搜索图标时)。 */
  function onSearchSubmit() {
    clearTimer()
    const term = searchTerm.value.trim()
    if (!term) {return}
    runSearch(term)
  }

  async function runSearch(term) {
    cancelInflight()
    searchAbortController = new AbortController()
    searchPanelState.value = 'loading'
    searchError.value = ''
    try {
      const results = await geocodeAddress(term, { signal: searchAbortController.signal })
      if (!results || !results.length) {
        searchResults.value = []
        searchPanelState.value = 'empty'
        return
      }
      searchResults.value = results
      searchPanelState.value = 'results'
    } catch (err) {
      if (err?.name === 'AbortError') {return}
      searchResults.value = []
      searchPanelState.value = 'empty'
      searchError.value = err?.message || '搜索失败'
    }
  }

  /** 在地图上选中一个搜索结果: 画 marker、zoom、关闭面板。 */
  function selectSearchResult(item) {
    if (!mapController?.value || !item) {return}
    searchMarker.value = {
      lon: item.lon,
      lat: item.lat,
      name: item.name,
      address: item.address,
    }
    mapController.value.zoomToPoint(item.lon, item.lat, 15)
    searchPanelOpen.value = false
    const label = item.source === 'coord'
      ? `已定位:${item.lon.toFixed(4)}, ${item.lat.toFixed(4)}`
      : `已定位:${item.name}`
    emit?.('notify', label)
    reverseGeocode(item.lon, item.lat).then((addr) => {
      if (addr && searchMarker.value && searchMarker.value.lon === item.lon) {
        searchMarker.value = { ...searchMarker.value, address: addr }
      }
    })
  }

  function closeSearchPanel() {
    searchPanelOpen.value = false
    searchPanelState.value = 'idle'
  }

  function clearSearch() {
    searchTerm.value = ''
    searchResults.value = []
    searchPanelState.value = 'idle'
    searchError.value = ''
    searchPanelOpen.value = false
    searchMarker.value = null
    cancelInflight()
  }

  /** 卸载时回收 timer / controller,避免页面关闭后仍然 setState。 */
  function dispose() {
    clearTimer()
    cancelInflight()
  }

  return {
    // state
    searchTerm,
    searchPanelOpen,
    searchPanelState,
    searchResults,
    searchError,
    searchMarker,
    geocoderKind,
    // actions
    onSearchInput,
    onSearchSubmit,
    selectSearchResult,
    closeSearchPanel,
    clearSearch,
    dispose,
  }
}
