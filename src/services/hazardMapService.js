import { dashboardApi } from './dashboardApi'

function normalizeFeature(feature) {
  const properties = feature?.properties ?? {}
  return {
    id: properties.id,
    name: properties.name,
    type: properties.type,
    level: properties.level,
    owner: properties.owner,
    status: properties.status,
    distance: properties.distance,
    lon: Number(properties.lon ?? feature?.geometry?.coordinates?.[0] ?? 0),
    lat: Number(properties.lat ?? feature?.geometry?.coordinates?.[1] ?? 0),
    regionCode: properties.regionCode,
  }
}

export async function loadHazardViewport({ bbox, zoom, level, keyword } = {}) {
  const data = await dashboardApi.getHazardsByViewport({ bbox, zoom, level, keyword })
  if (data?.type === 'FeatureCollection') {
    return (data.features ?? []).map(normalizeFeature)
  }
  return Array.isArray(data) ? data.map(normalizeFeature) : []
}

export async function loadDeviceViewport({ bbox, zoom, state, keyword } = {}) {
  const data = await dashboardApi.getDevicesByViewport({ bbox, zoom, state, keyword })
  return Array.isArray(data) ? data.map((item) => ({
    id: item.id,
    name: item.name,
    type: item.type,
    state: item.state,
    battery: item.battery,
    signal: item.signal,
    point: item.point,
    lon: Number(item.lon ?? 0),
    lat: Number(item.lat ?? 0),
  })) : []
}

export async function loadWarningViewport({ bbox, zoom, keyword } = {}) {
  const data = await dashboardApi.getWarningsByViewport({ bbox, zoom, keyword })
  return Array.isArray(data) ? data : []
}
