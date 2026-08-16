import 'ol/ol.css'
import Map from 'ol/Map'
import View from 'ol/View'
import TileLayer from 'ol/layer/Tile'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import Cluster from 'ol/source/Cluster'
import XYZ from 'ol/source/XYZ'
import OSM from 'ol/source/OSM'
import GeoJSON from 'ol/format/GeoJSON'
import { Attribution, defaults as defaultControls } from 'ol/control'
import { fromLonLat } from 'ol/proj'
import { bbox as bboxStrategy } from 'ol/loadingstrategy'
import { Circle as CircleStyle, Fill, Stroke, Style, Text } from 'ol/style'
import Feature from 'ol/Feature'
import Point from 'ol/geom/Point'
import LineString from 'ol/geom/LineString'

const tdtToken = import.meta.env.VITE_TDT_TOKEN || import.meta.env.VITE_TDT_TK || ''
const cesiumToken = import.meta.env.VITE_CESIUM_ION_TOKEN || ''
const vectorFallback = import.meta.env.VITE_MAP_TILE_URL || 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const satelliteFallback = import.meta.env.VITE_SATELLITE_TILE_URL || 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'
const mapAttribution = import.meta.env.VITE_TDT_ATTRIBUTION || '地图数据'

function buildTdtUrl(layer) {
  if (!tdtToken) return ''
  return `https://t0.tianditu.gov.cn/${layer}_w/wmts?service=wmts&request=GetTile&version=1.0.0&layer=${layer}&style=default&tileMatrixSet=w&format=tiles&tileMatrix={z}&tileRow={y}&tileCol={x}&tk=${tdtToken}`
}

function tileLayerFromUrl(url, visible) {
  const source = url ? new XYZ({ url, crossOrigin: 'anonymous' }) : new OSM()
  return new TileLayer({ source, visible })
}

function createBaseLayers(mode) {
  const vector = tileLayerFromUrl(buildTdtUrl('vec') || vectorFallback, mode === 'vector')
  const vectorAnnoUrl = buildTdtUrl('cva')
  const vectorAnno = vectorAnnoUrl ? tileLayerFromUrl(vectorAnnoUrl, mode === 'vector') : null
  const satellite = tileLayerFromUrl(buildTdtUrl('img') || satelliteFallback, mode === 'satellite')
  const satelliteAnnoUrl = buildTdtUrl('cia')
  const satelliteAnno = satelliteAnnoUrl ? tileLayerFromUrl(satelliteAnnoUrl, mode === 'satellite') : null
  return { vector, vectorAnno, satellite, satelliteAnno }
}

function createClusterLayer(styleFn) {
  const source = new VectorSource({ strategy: bboxStrategy })
  const clusterSource = new Cluster({ distance: 42, source })
  const layer = new VectorLayer({
    source: clusterSource,
    style: (feature) => styleFn(feature),
  })
  return { source, clusterSource, layer }
}

export function createOpenLayersController(container, { center = [107.21, 30.61], zoom = 10, baseMap = 'vector' } = {}) {
  const baseLayers = createBaseLayers(baseMap)
  const hazardLayer = createClusterLayer((feature) => buildClusterStyle(feature, 'hazard'))
  const deviceLayer = createClusterLayer((feature) => buildClusterStyle(feature, 'device'))
  const warningLayer = createClusterLayer((feature) => buildClusterStyle(feature, 'warning'))
  const personnelLayer = createClusterLayer((feature) => buildClusterStyle(feature, 'personnel'))
  const personnelTrailLayer = new VectorLayer({
    source: new VectorSource(),
    style: (feature) => buildTrailStyle(feature),
  })

  const map = new Map({
    target: container,
    layers: [baseLayers.vector, baseLayers.vectorAnno, baseLayers.satellite, baseLayers.satelliteAnno, hazardLayer.layer, deviceLayer.layer, warningLayer.layer, personnelTrailLayer, personnelLayer.layer].filter(Boolean),
    controls: defaultControls({ attribution: false, zoom: true }),
    view: new View({
      center: fromLonLat(center),
      zoom,
      minZoom: 3,
      maxZoom: 19,
    }),
  })

  map.addControl(new Attribution({ collapsible: false, label: '©', tipLabel: mapAttribution }))

  function setBaseMap(nextMode) {
    const useVector = nextMode !== 'satellite'
    baseLayers.vector.setVisible(useVector)
    if (baseLayers.vectorAnno) baseLayers.vectorAnno.setVisible(useVector)
    baseLayers.satellite.setVisible(!useVector)
    if (baseLayers.satelliteAnno) baseLayers.satelliteAnno.setVisible(!useVector)
    map.renderSync()
    return useVector ? 'vector' : 'satellite'
  }

  function setCenter(lon, lat, nextZoom = map.getView().getZoom()) {
    map.getView().setCenter(fromLonLat([Number(lon), Number(lat)]))
    if (Number.isFinite(Number(nextZoom))) map.getView().setZoom(Number(nextZoom))
  }

  function fitItems(items = []) {
    const features = items.filter((item) => item.lon != null && item.lat != null).map((item) => new Feature({ geometry: new Point(fromLonLat([Number(item.lon), Number(item.lat)])) }))
    if (!features.length) return
    const merged = features.map((feature) => feature.getGeometry().getExtent()).reduce((acc, next, index) => {
      if (index === 0) return next.slice()
      acc[0] = Math.min(acc[0], next[0])
      acc[1] = Math.min(acc[1], next[1])
      acc[2] = Math.max(acc[2], next[2])
      acc[3] = Math.max(acc[3], next[3])
      return acc
    }, [])
    map.getView().fit(merged, { padding: [48, 48, 48, 48], maxZoom: 15, duration: 180 })
  }

  function setHazards(items = []) {
    fillPointFeatures(hazardLayer.source, items)
  }

  function setHazardsGeoJson(geojson) {
    setGeoJsonFeatures(hazardLayer.source, geojson)
  }

  function setDevices(items = []) {
    fillPointFeatures(deviceLayer.source, items)
  }

  function setDevicesGeoJson(geojson) {
    setGeoJsonFeatures(deviceLayer.source, geojson)
  }

  function setPersonnel(items = []) {
    fillPointFeatures(personnelLayer.source, items)
    setPersonnelTrails(items)
  }

  function setPersonnelGeoJson(geojson) {
    setGeoJsonFeatures(personnelLayer.source, geojson)
  }

  function setWarnings(items = []) {
    fillPointFeatures(warningLayer.source, items)
  }

  function setWarningsGeoJson(geojson) {
    setGeoJsonFeatures(warningLayer.source, geojson)
  }

  function setPersonnelTrails(items = []) {
    personnelTrailLayer.getSource().clear()
    items.forEach((item) => {
      if (!Array.isArray(item.route) || item.route.length < 2) return
      const coordinates = item.route.map((step) => fromLonLat([Number(step.lon), Number(step.lat)]))
      const trail = new Feature({ geometry: new LineString(coordinates) })
      trail.set('kind', 'personnel-trail')
      trail.set('name', item.name)
      personnelTrailLayer.getSource().addFeature(trail)
    })
  }

  function destroy() {
    map.setTarget(undefined)
  }

  return {
    map,
    destroy,
    getMode: () => (baseLayers.satellite.getVisible() ? 'satellite' : 'vector'),
    setMode: setBaseMap,
    setCenter,
    setHazards,
    setHazardsGeoJson,
    setDevices,
    setDevicesGeoJson,
    setWarnings,
    setWarningsGeoJson,
    setPersonnel,
    setPersonnelGeoJson,
    setPersonnelTrails,
    fitItems,
    baseLayers,
    layers: { hazardLayer, deviceLayer, warningLayer, personnelLayer, personnelTrailLayer },
  }
}

export async function createCesiumViewer(container, { center = [107.21, 30.61], point = null } = {}) {
  const Cesium = await import('cesium')
  if (cesiumToken) Cesium.Ion.defaultAccessToken = cesiumToken

  const overlay = ensureOverlay(container)
  overlay.style.display = 'block'
  overlay.style.visibility = 'visible'
  overlay.style.opacity = '1'

  const viewer = new Cesium.Viewer(overlay, {
    animation: false,
    timeline: false,
    geocoder: false,
    homeButton: false,
    sceneModePicker: false,
    baseLayerPicker: false,
    navigationHelpButton: false,
    fullscreenButton: false,
    infoBox: false,
    selectionIndicator: false,
    shouldAnimate: false,
    requestRenderMode: true,
    targetFrameRate: 30,
    imageryProvider: new Cesium.UrlTemplateImageryProvider({
      url: buildTdtUrl('img') || satelliteFallback,
      credit: mapAttribution,
      maximumLevel: 19,
    }),
  })

  viewer.scene.globe.depthTestAgainstTerrain = true
  viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString('#dfeaf3')
  viewer.scene.skyAtmosphere.show = true
  viewer.scene.backgroundColor = Cesium.Color.fromCssColorString('#dceef7')
  viewer.camera.setView({
    destination: Cesium.Cartesian3.fromDegrees(Number(center[0]), Number(center[1]), 6500),
    orientation: { heading: 0, pitch: Cesium.Math.toRadians(-38), roll: 0 },
  })

  if (point) addCesiumPoint({ Cesium, viewer }, point)
  viewer.resize()
  viewer.scene.requestRender()
  return { Cesium, viewer }
}

export function addCesiumPoint(viewerBundle, point) {
  if (!viewerBundle?.viewer || !point) return
  const { Cesium, viewer } = viewerBundle
  viewer.entities.removeAll()
  viewer.entities.add({
    position: Cesium.Cartesian3.fromDegrees(Number(point.lon), Number(point.lat), 30),
    point: {
      pixelSize: 14,
      color: Cesium.Color.fromCssColorString('#e27d5b'),
      outlineColor: Cesium.Color.WHITE,
      outlineWidth: 3,
    },
    label: {
      text: point.name,
      font: 'bold 14px sans-serif',
      fillColor: Cesium.Color.WHITE,
      outlineColor: Cesium.Color.BLACK,
      outlineWidth: 3,
      showBackground: true,
      backgroundColor: Cesium.Color.fromCssColorString('rgba(18,56,62,0.75)'),
      pixelOffset: new Cesium.Cartesian2(0, -28),
    },
  })
  viewer.camera.flyTo({ destination: Cesium.Cartesian3.fromDegrees(Number(point.lon), Number(point.lat), 4500), duration: 1.0 })
  viewer.scene.requestRender()
}

export function destroyCesiumViewer(viewerBundle) {
  if (viewerBundle?.viewer && !viewerBundle.viewer.isDestroyed()) {
    viewerBundle.viewer.destroy()
  }
}

export function buildClusterStyle(feature, type) {
  const features = feature.get('features') || []
  const size = features.length
  if (size > 1) {
    const palette = {
      hazard: '#e27d5b',
      device: '#4f7fd7',
      warning: '#e2a03a',
      personnel: '#5db486',
    }
    const color = palette[type] || '#4f8f99'
    return new Style({
      image: new CircleStyle({
        radius: Math.max(12, 8 + Math.min(10, size)),
        fill: new Fill({ color }),
        stroke: new Stroke({ color: '#ffffff', width: 2 }),
      }),
      text: new Text({
        text: String(size),
        fill: new Fill({ color: '#ffffff' }),
        font: 'bold 12px sans-serif',
      }),
    })
  }

  const item = features[0]
  const level = item?.get?.('level') || '正常'
  const state = item?.get?.('state') || '在线'
  const name = item?.get?.('name') || ''

  if (type === 'hazard') return buildHazardStyle(level, name)
  if (type === 'device') return buildDeviceStyle(state, name)
  if (type === 'warning') return buildWarningStyle(level, name)
  return buildPersonnelStyle(state, name)
}

export function buildWarningStyle(featureOrLevel = '预警') {
  const level = typeof featureOrLevel === 'string' ? featureOrLevel : featureOrLevel.get?.('level') || '预警'
  const color = level.includes('橙') || level.includes('红') ? '#e27d5b' : level.includes('黄') ? '#d7a546' : '#4f8f99'
  return new Style({
    image: new CircleStyle({ radius: 8, fill: new Fill({ color }), stroke: new Stroke({ color: '#ffffff', width: 2 }) }),
    text: new Text({
      text: level.includes('橙') || level.includes('红') ? '‼' : '!',
      fill: new Fill({ color: '#ffffff' }),
      stroke: new Stroke({ color, width: 2 }),
      offsetY: -15,
      font: 'bold 12px sans-serif',
    }),
  })
}

export function buildHazardStyle(featureOrLevel = '正常') {
  const level = typeof featureOrLevel === 'string' ? featureOrLevel : featureOrLevel.get?.('level') || '正常'
  const color = level.includes('高') ? '#e27d5b' : level.includes('关注') ? '#d7a546' : '#5db486'
  return new Style({
    image: new CircleStyle({ radius: 8, fill: new Fill({ color }), stroke: new Stroke({ color: '#ffffff', width: 2 }) }),
    text: new Text({
      text: level.includes('高') ? '!' : level.includes('关注') ? '?' : '✓',
      fill: new Fill({ color: '#ffffff' }),
      stroke: new Stroke({ color, width: 2 }),
      offsetY: -15,
      font: 'bold 12px sans-serif',
    }),
  })
}

export function buildDeviceStyle(featureOrState = '在线') {
  const state = typeof featureOrState === 'string' ? featureOrState : featureOrState.get?.('state') || '在线'
  const color = state === '在线' ? '#2f7f80' : '#d57b56'
  return new Style({
    image: new CircleStyle({ radius: 7, fill: new Fill({ color }), stroke: new Stroke({ color: '#ffffff', width: 2 }) }),
    text: new Text({
      text: '◆',
      fill: new Fill({ color: '#ffffff' }),
      stroke: new Stroke({ color, width: 2 }),
      offsetY: -14,
      font: 'bold 11px sans-serif',
    }),
  })
}

export function buildPersonnelStyle(featureOrState = '在线') {
  const state = typeof featureOrState === 'string' ? featureOrState : featureOrState.get?.('state') || '在线'
  const color = state === '在线' ? '#4d78d7' : '#c95b87'
  return new Style({
    image: new CircleStyle({ radius: 7, fill: new Fill({ color }), stroke: new Stroke({ color: '#ffffff', width: 2 }) }),
    text: new Text({
      text: '人',
      fill: new Fill({ color: '#ffffff' }),
      stroke: new Stroke({ color, width: 2 }),
      offsetY: -14,
      font: 'bold 11px sans-serif',
    }),
  })
}

export function buildPersonnelTooltip(person) {
  if (!person) return ''
  return [person.name, person.role, person.team, person.state, person.updatedAt].filter(Boolean).join(' · ')
}

function fillPointFeatures(source, items = []) {
  source.clear()
  items.forEach((item) => {
    if (item.lon == null || item.lat == null) return
    source.addFeature(new Feature({ geometry: new Point(fromLonLat([Number(item.lon), Number(item.lat)])), ...item }))
  })
}

function setGeoJsonFeatures(source, geojson) {
  const format = new GeoJSON()
  source.clear()
  if (!geojson) return
  source.addFeatures(format.readFeatures(geojson, { featureProjection: 'EPSG:3857' }))
}

function ensureOverlay(container) {
  let overlay = container.querySelector('.cesium-map-overlay')
  if (!overlay) {
    overlay = document.createElement('div')
    overlay.className = 'cesium-map-overlay'
    overlay.style.cssText = 'position:absolute;inset:0;display:none;visibility:hidden;opacity:0;'
    container.appendChild(overlay)
  }
  return overlay
}
