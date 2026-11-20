/**
 * 总览页-地图地址/坐标搜索 composable。
 *
 * 把搜索面板的状态机、AbortController、选点-缩放、反向地理补全
 * 这套原本散落在 OverviewPage.vue 第 178-1102 行 ~130 行的逻辑收敛到这里,
 * 让 OverviewPage.vue 只剩 `<MapSearchBox v-bind="search.bind" />` 一行接入。
 *
 * 状态机:
 *   idle    输入框为空、没展开候选项
 *   loading 已发起搜索,等待返回
 *   results 拿到候选,渲染下拉
 *   empty   搜索无结果
 */

import { ref } from 'vue'
import {
  geocodeAddress,
  reverseGeocode,
  parseCoordinate,
} from '../services/_architecture/core/geocodeService.js'

export function useOverviewSearch({ mapController, hazards, emit, onSelect }) {
  const searchTerm = ref('')
  const searchPanelOpen = ref(false)
  const searchPanelState = ref('idle')
  const searchResults = ref([])
  const searchError = ref('')
  const searchMarker = ref(null)

  let searchAbortController = null

  function cancelInflight() {
    if (searchAbortController) {
      searchAbortController.abort()
      searchAbortController = null
    }
  }

  function resetPanel() {
    searchPanelState.value = 'idle'
    searchResults.value = []
    searchError.value = ''
  }

  function onSearchInput() {
    cancelInflight()
    resetPanel()
    searchPanelOpen.value = Boolean(searchTerm.value.trim())
  }

  /** 主动跑一次搜索(用户按 Enter 或点击搜索图标时)。 */
  function onSearchSubmit(event) {
    if (event?.isComposing || event?.keyCode === 229) {
      return
    }
    event?.preventDefault()
    const term = searchTerm.value.trim()
    if (!term) {
      return
    }
    runSearch(term)
  }

  async function runSearch(term) {
    cancelInflight()
    const controller = new AbortController()
    searchAbortController = controller
    searchPanelOpen.value = true
    searchPanelState.value = 'loading'
    searchError.value = ''
    try {
      const keyword = term.toLowerCase()
      const localResults = (hazards?.value ?? [])
        .filter(
          (point) =>
            [point.name, point.hazardCode, point.address].some((value) =>
              String(value ?? '')
                .toLowerCase()
                .includes(keyword)
            ) &&
            Number.isFinite(point.lon) &&
            Number.isFinite(point.lat)
        )
        .slice(0, 8)
        .map((point) => ({
          lon: point.lon,
          lat: point.lat,
          name: point.name,
          address: point.address,
          source: 'hazard',
        }))
      const results =
        !parseCoordinate(term) && localResults.length
          ? localResults
          : await geocodeAddress(term, { signal: controller.signal })
      if (controller.signal.aborted) {
        return
      }
      if (!results || !results.length) {
        searchResults.value = []
        searchPanelState.value = 'empty'
        return
      }
      searchResults.value = results
      searchPanelState.value = 'results'
    } catch (err) {
      if (controller.signal.aborted) {
        return
      }
      searchResults.value = []
      searchPanelState.value = 'empty'
      searchError.value = err?.message || '搜索失败'
    }
  }

  /** 在地图上选中一个搜索结果: 画 marker、zoom、关闭面板。 */
  function selectSearchResult(item) {
    if (!mapController?.value || !item) {
      return
    }
    searchMarker.value = {
      lon: item.lon,
      lat: item.lat,
      name: item.name,
      address: item.address,
    }
    mapController.value.zoomToPoint(item.lon, item.lat, 15)
    onSelect?.(item)
    searchPanelOpen.value = false
    const label =
      item.source === 'coord'
        ? `已定位:${item.lon.toFixed(4)}, ${item.lat.toFixed(4)}`
        : `已定位:${item.name}`
    emit?.('notify', label)
    if (item.source !== 'coord') {
      return
    }
    const marker = searchMarker.value
    reverseGeocode(item.lon, item.lat).then((addr) => {
      if (addr && searchMarker.value === marker) {
        searchMarker.value = { ...searchMarker.value, address: addr }
      }
    })
  }

  function closeSearchPanel() {
    cancelInflight()
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

  /** 卸载时回收 controller,避免页面关闭后仍然 setState。 */
  function dispose() {
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
    // actions
    onSearchInput,
    onSearchSubmit,
    selectSearchResult,
    closeSearchPanel,
    clearSearch,
    dispose,
  }
}
