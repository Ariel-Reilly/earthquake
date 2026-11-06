import 'ol/ol.css'
import Map from 'ol/Map'
import View from 'ol/View'
import TileLayer from 'ol/layer/Tile'
import ImageLayer from 'ol/layer/Image'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import ImageStaticSource from 'ol/source/ImageStatic'
import Cluster from 'ol/source/Cluster'
import XYZ from 'ol/source/XYZ'
import GeoJSON from 'ol/format/GeoJSON'
import { Attribution, ScaleLine, defaults as defaultControls } from 'ol/control'
import { fromLonLat } from 'ol/proj'
import { bbox as bboxStrategy } from 'ol/loadingstrategy'
import { Circle as CircleStyle, Fill, Stroke, Style, Text } from 'ol/style'
import Polygon from 'ol/geom/Polygon'
import Feature from 'ol/Feature'
import Point from 'ol/geom/Point'
import LineString from 'ol/geom/LineString'
import { wgs84ToGcj02, gcj02ToWgs84 } from '../../../utils/coordTransform.js'

/**
 * 卫星影像底图 v2.0（2026-11-05 重构）
 *
 * 背景：旧实现把「卫星底图」做成一次异步 AI 合成任务（Sentinel-1 SAR + Sentinel-2 真彩），
 * 切到卫星模式时先隐藏矢量底图、合成图层又是 1x1 透明占位且 visible:false → 必然白屏；
 * 合成结果常返回 file:// / mock:// / localhost:8001 等浏览器不可达地址；
 * 兜底矢量底图是境外 OSM（实测 CONNECT tunnel 502），所以经常性「合成失败 + 白屏」。
 *
 * 新实现：直接加载合规卫星影像**瓦片金字塔**，不再有任何合成任务、不再有境外源。
 *   - 主源：高德影像瓦片 style=6（实测 HTTP 200 / image/jpeg / 29KB@z10，支持到 18~19 级）
 *   - 注记：高德影像注记 style=8（透明 PNG，默认关闭，可在图层面板开启）
 *   - 备源：天地图 img_w WMTS（需 VITE_TDT_TOKEN，用户到天地图开放平台自行申请），
 *           未配置时使用高德备用子域；主源连续失败自动切换。
 *   - 禁止：OSM / ArcGIS / Mapbox / Google / Cesium Ion / Sentinel STAC 等境外源。
 *
 * 坐标系：高德瓦片为 GCJ-02，业务点位存 WGS-84，由 applyDatum() 偏移；
 *         天地图为 CGCS2000（≈WGS-84），切到备源时把 pointDatum 改回 wgs84 并重投影。
 */
const AMAP_IMAGERY_HOSTS = [
  'webst01.is.autonavi.com',
  'webst02.is.autonavi.com',
  'webst03.is.autonavi.com',
  'webst04.is.autonavi.com',
]
const TDT_TOKEN = import.meta.env.VITE_TDT_TOKEN || import.meta.env.VITE_TDT_TK || ''
// Cesium Ion 是境外商业影像服务，无合规资质且默认 token 必然失败，已彻底停用。
const mapAttribution = '© 高德地图 · 卫星影像'
// 渲染分辨率倍率：把 OL / Cesium 画布拉到设备原生像素密度，让卫星瓦片在高清屏上不发糊。
// 用户可通过 VITE_MAP_PIXEL_RATIO 强制覆盖，默认取 window.devicePixelRatio 但封顶 2.5，
// 避免 3x/4x 屏带来 9–16 倍渲染开销。
function resolvePixelRatio() {
  const override = Number(import.meta.env.VITE_MAP_PIXEL_RATIO)
  if (Number.isFinite(override) && override > 0) {return override}
  return Math.min(window.devicePixelRatio || 1, 2.5)
}

/**
 * 把 GPS 精度米数映射成地图缩放级别。
 * 原则：accuracy 圈要被完整看到，不能放大到圈外看不见。
 *   ±< 50 m    → zoom 16  小区级（GPS 精确）
 *   ±50–200 m  → zoom 14  街区级
 *   ±200–1000 m → zoom 12 区域级
 *   ±1–5 km   → zoom 10 城市级
 *   ±>5 km    → zoom 8  省份级（IP/WiFi 兜底）
 * 调用方传入的 `fallbackZoom` 是用户期望的"理想精度"，仅在精度优于该等级时才生效。
 *
 * 提到模块级以便 UI 层（如 OverviewPage 区域环境面板、WeatherPage）复用。
 */
export function pickZoomForAccuracy(accuracy, fallbackZoom) {
  if (!Number.isFinite(fallbackZoom)) {fallbackZoom = 16}
  if (!Number.isFinite(accuracy) || accuracy <= 0) {return fallbackZoom}
  if (accuracy <= 50) {return Math.min(fallbackZoom, 16)}
  if (accuracy <= 200) {return Math.min(fallbackZoom, 14)}
  if (accuracy <= 1000) {return Math.min(fallbackZoom, 12)}
  if (accuracy <= 5000) {return Math.min(fallbackZoom, 10)}
  return Math.min(fallbackZoom, 8)
}

/**
 * 把精度米数分档，返回 { grade, label, color } 供 UI 上色与文案用。
 * grade: 0 精确 / 1 良好 / 2 粗略 / 3 仅参考。
 * 模块级导出，便于面板组件在拿到 cached 经纬度后直接显示档位徽标。
 */
export function gradeAccuracy(accuracy) {
  if (!Number.isFinite(accuracy) || accuracy <= 0) {
    return { grade: 1, label: '精度未知', color: 'amber' }
  }
  if (accuracy <= 50) {return { grade: 0, label: '精确', color: 'teal' }}
  if (accuracy <= 500) {return { grade: 1, label: '良好', color: 'teal' }}
  if (accuracy <= 2000) {return { grade: 2, label: '粗略', color: 'amber' }}
  return { grade: 3, label: '仅供参考', color: 'red' }
}

/** 天地图影像 WMTS。需用户自行到天地图开放平台申请 tk（VITE_TDT_TOKEN），未配置返回空串。 */
function buildTdtUrl(layer) {
  if (!TDT_TOKEN) {return ''}
  return `https://t0.tianditu.gov.cn/${layer}_w/wmts?service=wmts&request=GetTile&version=1.0.0&layer=${layer}&style=default&tileMatrixSet=w&format=tiles&tileMatrix={z}&tileRow={y}&tileCol={x}&tk=${encodeURIComponent(TDT_TOKEN)}`
}

/**
 * 高德影像瓦片。style=6 卫星影像（实测 HTTP 200 / image/jpeg），style=8 影像注记（透明 PNG）。
 * 这是国内具备测绘资质服务商的公开影像瓦片服务，当前不需要 key；若后续改成需鉴权，
 * 把自有 key 配到 VITE_AMAP_TILE_KEY 即可 —— 代码里绝不内置任何有效 key。
 */
function buildAmapImageryUrl(style, hostIndex = 0) {
  const host = AMAP_IMAGERY_HOSTS[hostIndex % AMAP_IMAGERY_HOSTS.length]
  const amapTileKey = import.meta.env.VITE_AMAP_TILE_KEY || ''
  const keyPart = amapTileKey ? `&key=${encodeURIComponent(amapTileKey)}` : ''
  return `https://${host}/appmaptile?style=${style}&x={x}&y={y}&z={z}${keyPart}`
}

/** GCJ-02 ↔ WGS-84 转换实现已抽到 utils/coordTransform.js。 */

function tileLayerFromUrl(url, visible, tileSize, sourceMeta = null) {
  const source = new XYZ({
    url,
    crossOrigin: 'anonymous',
    ...(tileSize ? { tileSize } : {}),
  })
  const layer = new TileLayer({ source, visible })
  layer.set('sourceMeta', sourceMeta)
  return layer
}

/**
 * v1.8.0：Sentinel 合成图层（S2 真彩 + S1 SAR 伪彩色）。
 * - extent 用 EPSG:4326 经纬度，OL 内部按视图投影重投影
 * - 默认 visible:false，由 UI 触发"合成卫星"按钮后写入 URL 并 visible=true
 * - 使用 ImageStaticSource 而不是 TileLayer，因为合成结果是单张大图覆盖 AOI，不是瓦片金字塔
 *   若后端返回 WMTS 模板，按需切换为 XYZ / TileLayer
 */
function createSentinelLayers() {
  const placeholder = makeTransparentPlaceholderDataUrl()
  const makeLayer = (kind, opacity, zIndex) => {
    const source = new ImageStaticSource({
      url: placeholder,
      imageExtent: [107.0, 30.4, 107.4, 30.8],
      projection: 'EPSG:4326',
      crossOrigin: undefined,
    })
    return new ImageLayer({
      source,
      opacity,
      visible: false,
      zIndex,
      properties: { kind },
    })
  }
  return {
    s2TrueColor: makeLayer('s2-truecolor', 0.95, 90),
    sarFalseColor: makeLayer('s1-sar-falsecolor', 0.55, 91),
  }
}

/**
 * v1.8.0：Sentinel-1（雷达 SAR）+ Sentinel-2（光学真彩）合成卫星底图。
 *
 * 矢量底图：仍走天地图 vec_w / cva → 高德 style=7 → OSM 兜底（与点位坐标系一致）
 * 卫星底图：不再使用通用商业卫星，改为由 AI Service /sentinel/compose 返回的合成图
 *   - s2TrueColor（S2 真彩）作为 Z=90 的底层
 *   - sarFalseColor（S1 SAR 伪彩色）作为 Z=91 的叠加层
 *   - 两个图层使用同一个 AOI extent，由 setSentinelOverlay() 一次性更新
 *   - 默认不可见，由 UI "合成卫星" 按钮触发 fetch → poll → setSentinelOverlay 流程
 */
function createBaseLayers(mode) {
  // ① 主影像：高德卫星影像瓦片（真实瓦片金字塔，zoom 3~19，不再需要任何合成任务）
  const primaryUrl = buildAmapImageryUrl(6, 0)
  const satellite = tileLayerFromUrl(primaryUrl, true, 256, {
    kind: 'amap-imagery', name: '高德卫星影像', datum: 'gcj02',
    updatedAt: new Date().toISOString(), priority: 1,
  })
  // ② 影像注记（透明 PNG，不是矢量底图）：mode=vector 时叠加地名，mode=satellite 时纯影像
  const satelliteAnno = tileLayerFromUrl(buildAmapImageryUrl(8, 0), mode !== 'satellite', 256, {
    kind: 'amap-imagery-anno', name: '影像地名注记', datum: 'gcj02', priority: 2,
  })
  // ③ 备影像：优先天地图 img_w（需 tk），否则高德备用子域；仅在主源连续失败时接管
  const tdtImgUrl = buildTdtUrl('img')
  const backupUrl = tdtImgUrl || buildAmapImageryUrl(6, 1)
  const backupDatum = tdtImgUrl ? 'wgs84' : 'gcj02'
  const vector = tileLayerFromUrl(backupUrl, false, 256, {
    kind: tdtImgUrl ? 'tdt-imagery' : 'amap-imagery-backup',
    name: tdtImgUrl ? '天地图卫星影像（备源）' : '高德卫星影像（备用子域）',
    datum: backupDatum, priority: 3,
  })
  const tdtAnnoUrl = buildTdtUrl('cia')
  const vectorAnno = tdtAnnoUrl
    ? tileLayerFromUrl(tdtAnnoUrl, false, 256, { kind: 'tdt-imagery-anno', name: '天地图注记', datum: 'wgs84', priority: 4 })
    : null
  // ④ 保留旧 Sentinel 叠加层 API 形状（默认全隐藏），仅为向后兼容存量调用，不参与底图显示
  const sentinelLayers = createSentinelLayers()
  return {
    vector,
    vectorAnno,
    satellite,
    satelliteAnno,
    vectorDatum: 'gcj02',
    satelliteDatum: 'gcj02',
    backupDatum,
    sentinelLayers,
    primaryUrl,
    backupUrl,
  }
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

/**
 * 国家气象 overlay 图层工厂：返回 { satellite, radar, precip24h, snow24h, drought }
 * 五个 ImageLayer，每层绑定一个待刷新的 ImageStaticSource。
 *   - extent 用 EPSG:4326 经纬度坐标，OL 内部会按视图投影（3857）重投影
 *   - 默认 visible:false，UI 上由图例开关控制
 *   - source 故意指向 1x1 透明 PNG 占位，等 URL 解析完成后再 refreshSource
 */
function createWeatherOverlayLayers() {
  const placeholder = makeTransparentPlaceholderDataUrl()
  const makeLayer = (kind) => {
    const extent = WEATHER_EXTENTS[kind]
    const source = new ImageStaticSource({
      url: placeholder,
      imageExtent: extent,
      projection: 'EPSG:4326',
      crossOrigin: undefined,
    })
    return new ImageLayer({
      source,
      opacity: 0.65,
      visible: false,
      zIndex: 200,
      properties: { kind },
    })
  }
  return {
    satellite: makeLayer('satellite'),
    radar: makeLayer('radar'),
    precip24h: makeLayer('precip24h'),
    snow24h: makeLayer('snow24h'),
    drought: makeLayer('drought'),
  }
}

/** 1x1 透明 PNG 的 data URL，作为占位避免 OL 报错。 */
function makeTransparentPlaceholderDataUrl() {
  return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==='
}

const WEATHER_EXTENTS = {
  // 风云四号 A 中国区域：约 0-60°N, 70-140°E
  satellite: [70, 0, 140, 60],
  // NMC 全国雷达 / 24h 降水 / 积雪 / 干旱：覆盖行政区划
  radar: [73, 16, 135, 54],
  precip24h: [73, 16, 135, 54],
  snow24h: [73, 16, 135, 54],
  drought: [73, 16, 135, 54],
}

export function createOpenLayersController(container, { center = [107.21, 30.61], zoom = 10, baseMap = 'vector' } = {}) {
  const baseLayers = createBaseLayers(baseMap)
  // 两种模式都是卫星影像：satellite=纯影像，vector=影像+地名注记。不存在矢量底图。
  let currentMode = baseMap === 'satellite' ? 'satellite' : 'vector'
  // 主影像（高德）为 GCJ-02；故障转移到天地图备源时切回 wgs84 并重投影全部点位。
  // pointDatum 必须跟随当前可见底图，否则点位会出现 50–500m 系统性偏移。
  let pointDatum = baseLayers.satelliteDatum
  let backupActive = false
  // 当前选中的灾害点 id（用于放大后高亮该详细点）
  let selectedHazardId = null
  const hazardLayer = createClusterLayer((feature) => buildClusterStyle(feature, 'hazard', selectedHazardId))
  const deviceLayer = createClusterLayer((feature) => buildClusterStyle(feature, 'device'))
  const warningLayer = createClusterLayer((feature) => buildClusterStyle(feature, 'warning'))
  const personnelLayer = createClusterLayer((feature) => buildClusterStyle(feature, 'personnel'))
  const personnelTrailLayer = new VectorLayer({
    source: new VectorSource(),
    style: (feature) => buildTrailStyle(feature),
  })

  // GPS 定位图层：独立放在 hazard/device 之上，避免被聚类覆盖在视野下方
  const userLocationSource = new VectorSource()
  // 这里先声明但暂不赋值：map.getView() 要等下面 new Map({...}) 之后才有；
  // OL 的 style 函数是延迟调用，等真正需要渲染 feature 时 userLocationView 早已赋值，
  // 用 ?. 防御性兜一下，避免在样式函数第一次执行时拿到 "null.getResolution()"
  let userLocationView = null
  const userLocationLayer = new VectorLayer({
    source: userLocationSource,
    style: (feature) => buildUserLocationStyle(feature, userLocationView?.getResolution()),
  })

  // 国家气象 overlay 图层：卫星云图 / 雷达回波 / 24h 降水 / 24h 积雪 / 干旱监测
  // 用 ImageStatic 渲染最新一帧 JPG/PNG，扩展 imageExtent 配置到中国区域（EPSG:4326 经纬度），
  // OL 会在内部按当前视图自动把图片投影到 3857。默认全部隐藏，由 UI 上的图例开关控制。
  const weatherOverlayLayers = createWeatherOverlayLayers()

  const map = new Map({
    target: container,
    // 显示为设备原生像素密度，避免高清屏上瓦片被拉伸发糊；OL 默认就用 devicePixelRatio，
    // 这里显式声明并封顶到 2.5，行为更可预期
    pixelRatio: resolvePixelRatio(),
    // 顺序：底图 → 注记 → 气象 overlay → 业务图层；overlay 排在业务图层之下，避免遮挡标记
    layers: [
      // 卫星影像常驻最底层且始终可见；备影像层平时隐藏，仅在主源连续失败时接管
      baseLayers.satellite,
      baseLayers.vector,
      baseLayers.satelliteAnno,
      baseLayers.vectorAnno,
      weatherOverlayLayers.satellite,
      weatherOverlayLayers.radar,
      weatherOverlayLayers.precip24h,
      weatherOverlayLayers.snow24h,
      weatherOverlayLayers.drought,
      hazardLayer.layer,
      deviceLayer.layer,
      warningLayer.layer,
      personnelTrailLayer,
      personnelLayer.layer,
      userLocationLayer,
    ].filter(Boolean),
    controls: defaultControls({ attribution: false, zoom: true }),
    view: new View({
      center: fromLonLat(applyDatum(center, pointDatum)),
      zoom,
      minZoom: 3,
      maxZoom: 19,
    }),
  })
  userLocationView = map.getView()
  // 用户缩放地图时，accuracy 圈的像素半径需要随分辨率刷新
  userLocationView.on('change:resolution', () => userLocationLayer.changed())

  map.addControl(new Attribution({ collapsible: false, label: '©', tipLabel: mapAttribution }))
  // 比例尺：OL 内置 ScaleLine 每次渲染按「当前视图真实分辨率 × 纬度校正」重算。
  // 关键开关：
  //   bar:true   → 同时画两段刻度（外圈 = 整数 km/100m，内圈 = 0.5× 外圈），距离感更接近真实地图
  //   steps:4    → 4 个刻度选项供 OL 自适应（避免极缩放时只画 "1m" 这种无效值）
  //   minWidth   → 最小像素宽度，保证缩到很小时仍可读
  // 切勿写死数值或手工近似 —— 用户报"比例尺误差"基本都来自底图声明的 tileSize 与真实瓦片不一致；
  // 这里 bar:true + metric units 让 OL 自适应选择最贴近的 km/m 刻度，避免人为填值。
  map.addControl(new ScaleLine({ units: 'metric', bar: true, steps: 4, minWidth: 120 }))

  // WGS-84 原点缓存
  // 每个业务图层单独存一份"WGS-84 原始数据"，切底图时按新 pointDatum 重新投影。
  // 不存缓存就只能一直持有"上一次画的 datum"版本，再切回原底图点位仍然偏移。
  const itemRegistry = {
    hazards: [],
    devices: [],
    warnings: [],
    personnel: [],
    /** 轨迹坐标使用经纬度数组，单独存 */
    personnelTrails: [],
    /** 用户当前定位：{lon, lat, accuracy, timestamp} 单条记录 */
    userLocation: null,
  }

  /** 切换 datum 后把所有已绘制的 WGS-84 原点重新投影（主源 GCJ-02 ↔ 天地图备源 WGS-84）。 */
  function reprojectAllPoints(nextDatum) {
    if (nextDatum === pointDatum) {return}
    pointDatum = nextDatum
    fillPointFeatures(hazardLayer.source, itemRegistry.hazards, pointDatum)
    fillPointFeatures(deviceLayer.source, itemRegistry.devices, pointDatum)
    fillPointFeatures(warningLayer.source, itemRegistry.warnings, pointDatum)
    fillPointFeatures(personnelLayer.source, itemRegistry.personnel, pointDatum)
    personnelTrailLayer.getSource().clear()
    itemRegistry.personnelTrails.forEach((item) => {
      if (!Array.isArray(item.route) || item.route.length < 2) {return}
      const coordinates = item.route.map((step) =>
        fromLonLat(applyDatum([Number(step.lon), Number(step.lat)], pointDatum)),
      )
      const trail = new Feature({ geometry: new LineString(coordinates) })
      trail.set('kind', 'personnel-trail')
      trail.set('name', item.name)
      personnelTrailLayer.getSource().addFeature(trail)
    })
    if (itemRegistry.userLocation) {
      const u = itemRegistry.userLocation
      userLocationSource.clear()
      const [projLon, projLat] = applyDatum([u.lon, u.lat], pointDatum)
      const feature = new Feature({ geometry: new Point(fromLonLat([projLon, projLat])) })
      feature.set('kind', 'user-location')
      feature.set('lon', u.lon)
      feature.set('lat', u.lat)
      feature.set('accuracy', u.accuracy)
      feature.set('timestamp', u.timestamp)
      userLocationSource.addFeature(feature)
    }
  }

  /**
   * 切换底图模式。两种模式都是卫星影像瓦片，差别只在是否叠加地名注记：
   *   satellite → 纯卫星影像；vector → 卫星影像 + 地名注记（兼容历史 UI 命名）。
   * 关键修复：主影像层**永远可见**，不会再出现「切到卫星先白屏、合成失败也不回滚」。
   */
  function setBaseMap(nextMode) {
    currentMode = nextMode === 'satellite' ? 'satellite' : 'vector'
    const showAnno = currentMode === 'vector' && !backupActive
    baseLayers.satellite.setVisible(!backupActive)
    baseLayers.vector.setVisible(backupActive)
    if (baseLayers.satelliteAnno) {baseLayers.satelliteAnno.setVisible(showAnno)}
    if (baseLayers.vectorAnno) {baseLayers.vectorAnno.setVisible(backupActive && currentMode === 'vector')}
    map.renderSync()
    return currentMode
  }

  /* ─── 瓦片失败可见化 + 自动切换备源 ─────────────────────────────── */
  const tileErrorListeners = new Set()
  let tileErrorCount = 0
  let tileErrorTimer = null
  function emitTileError(message) {
    tileErrorListeners.forEach((fn) => {
      try {fn(message)} catch (_) { /* 回调异常不能打断地图渲染 */ }
    })
  }
  function activateBackup(reason) {
    if (backupActive) {return}
    backupActive = true
    baseLayers.vector.setVisible(true)
    baseLayers.satellite.setVisible(false)
    if (baseLayers.satelliteAnno) {baseLayers.satelliteAnno.setVisible(false)}
    if (baseLayers.vectorAnno) {baseLayers.vectorAnno.setVisible(currentMode === 'vector')}
    reprojectAllPoints(baseLayers.backupDatum)
    baseLayers.vector.getSource().refresh()
    map.renderSync()
    emitTileError(`主卫星影像加载失败（${reason}），已切换到${baseLayers.vector.get('sourceMeta')?.name || '备用影像源'}`)
  }
  function bindTileError(layer, isBackup) {
    const source = layer?.getSource?.()
    if (!source || typeof source.on !== 'function') {return}
    source.on('tileloaderror', () => {
      if (isBackup) {
        emitTileError('备用卫星影像源同样加载失败，请检查网络或 VITE_TDT_TOKEN 配置')
        return
      }
      tileErrorCount += 1
      if (tileErrorCount < 4) {
        if (tileErrorTimer) {clearTimeout(tileErrorTimer)}
        tileErrorTimer = setTimeout(() => {tileErrorCount = 0}, 8000)
        return
      }
      if (tileErrorTimer) {clearTimeout(tileErrorTimer)}
      activateBackup(`${tileErrorCount} 个瓦片请求失败`)
      tileErrorCount = 0
    })
  }
  bindTileError(baseLayers.satellite, false)
  bindTileError(baseLayers.vector, true)

  /**
   * v1.8.0：把合成好的 Sentinel-1+2 图层挂到地图上。
   *   - s2Url  ：S2 真彩合成图 URL（已上传到对象存储 / CDN，可直接 <img> 加载）
   *   - sarUrl ：S1 SAR 伪彩色合成图 URL（与 s2Url 同一 AOI、同一 EPSG:4326 extent）
   *   - bbox   ：[minLon, minLat, maxLon, maxLat] AOI 范围，与图像对齐
   *   - meta   ：可选元数据，写到 layer.properties 供 UI 展示（合成时间、来源 provider）
   *
   * 调用方应当先调 sentinelService.composeSentinelScene / pollSentinelJob，
   * 等 status=succeeded 后拿到 result.s2Url / result.sarUrl，再走这里挂图。
   */
  function setSentinelOverlay({ s2Url, sarUrl, bbox, meta } = {}) {
    const extent = sanitizeBboxForOverlay(bbox)
    if (!extent) {
      throw new Error('setSentinelOverlay 需要合法 bbox：[minLon, minLat, maxLon, maxLat]')
    }
    if (s2Url) {
      baseLayers.sentinelLayers.s2TrueColor.setSource(
        new ImageStaticSource({ url: s2Url, imageExtent: extent, projection: 'EPSG:4326', crossOrigin: undefined }),
      )
      baseLayers.sentinelLayers.s2TrueColor.set('sourceMeta', {
        kind: 's2-truecolor',
        name: 'Sentinel-2 真彩',
        bbox: extent,
        meta,
        updatedAt: new Date().toISOString(),
      })
      baseLayers.sentinelLayers.s2TrueColor.setVisible(true)
    }
    if (sarUrl) {
      baseLayers.sentinelLayers.sarFalseColor.setSource(
        new ImageStaticSource({ url: sarUrl, imageExtent: extent, projection: 'EPSG:4326', crossOrigin: undefined }),
      )
      baseLayers.sentinelLayers.sarFalseColor.set('sourceMeta', {
        kind: 's1-sar-falsecolor',
        name: 'Sentinel-1 SAR 伪彩色（VV/VH/VV÷VH）',
        bbox: extent,
        meta,
        updatedAt: new Date().toISOString(),
      })
      baseLayers.sentinelLayers.sarFalseColor.setVisible(true)
    }
    if (extent) {
      // 合成结果回到地图上时，地图视野聚焦到 AOI
      const [minLon, minLat, maxLon, maxLat] = extent
      const ring = [
        fromLonLat([minLon, minLat]),
        fromLonLat([maxLon, minLat]),
        fromLonLat([maxLon, maxLat]),
        fromLonLat([minLon, maxLat]),
        fromLonLat([minLon, minLat]),
      ]
      const polygon = new Polygon([ring])
      map.getView().fit(polygon.getExtent(), { padding: [64, 64, 64, 64], duration: 480, maxZoom: 14 })
    }
    map.renderSync()
    return {
      s2Layer: baseLayers.sentinelLayers.s2TrueColor.get('sourceMeta'),
      sarLayer: baseLayers.sentinelLayers.sarFalseColor.get('sourceMeta'),
    }
  }

  /** 清除当前挂载的 Sentinel 合成图层（回到占位 + 隐藏）。 */
  function clearSentinelOverlay() {
    const placeholder = makeTransparentPlaceholderDataUrl()
    baseLayers.sentinelLayers.s2TrueColor.setSource(
      new ImageStaticSource({
        url: placeholder,
        imageExtent: [107.0, 30.4, 107.4, 30.8],
        projection: 'EPSG:4326',
        crossOrigin: undefined,
      }),
    )
    baseLayers.sentinelLayers.s2TrueColor.set('sourceMeta', null)
    baseLayers.sentinelLayers.s2TrueColor.setVisible(false)
    baseLayers.sentinelLayers.sarFalseColor.setSource(
      new ImageStaticSource({
        url: placeholder,
        imageExtent: [107.0, 30.4, 107.4, 30.8],
        projection: 'EPSG:4326',
        crossOrigin: undefined,
      }),
    )
    baseLayers.sentinelLayers.sarFalseColor.set('sourceMeta', null)
    baseLayers.sentinelLayers.sarFalseColor.setVisible(false)
    map.renderSync()
  }

  /** v1.8.0：合成元数据（含合成时间、provider、bbox），UI 用于右下角时间戳徽章。 */
  function getSentinelMeta() {
    return {
      s2: baseLayers.sentinelLayers.s2TrueColor.get('sourceMeta') || null,
      sar: baseLayers.sentinelLayers.sarFalseColor.get('sourceMeta') || null,
    }
  }

  /** v1.8.0：单独调节 SAR 伪彩色叠加不透明度（0-1）。 */
  function setSentinelSarOpacity(opacity) {
    baseLayers.sentinelLayers.sarFalseColor.setOpacity(Math.max(0, Math.min(1, Number(opacity) || 0)))
    map.renderSync()
  }

  /** v1.8.0：单独调节 S2 真彩底色不透明度（0-1）。 */
  function setSentinelS2Opacity(opacity) {
    baseLayers.sentinelLayers.s2TrueColor.setOpacity(Math.max(0, Math.min(1, Number(opacity) || 0)))
    map.renderSync()
  }

  function sanitizeBboxForOverlay(bbox) {
    if (!Array.isArray(bbox) || bbox.length !== 4) {return null}
    const [minLon, minLat, maxLon, maxLat] = bbox.map(Number)
    if (![minLon, minLat, maxLon, maxLat].every(Number.isFinite)) {return null}
    if (minLon >= maxLon || minLat >= maxLat) {return null}
    return [minLon, minLat, maxLon, maxLat]
  }

  function setCenter(lon, lat, nextZoom = map.getView().getZoom()) {
    const transformed = applyDatum([Number(lon), Number(lat)], pointDatum)
    map.getView().setCenter(fromLonLat(transformed))
    if (Number.isFinite(Number(nextZoom))) {map.getView().setZoom(Number(nextZoom))}
  }

  /** 平滑聚焦到指定坐标并放大到目标级别（用于点击风险点后定位详细点）。 */
  function zoomToPoint(lon, lat, nextZoom = 15) {
    const view = map.getView()
    const zoom = Number(nextZoom)
    const transformed = applyDatum([Number(lon), Number(lat)], pointDatum)
    view.animate({
      center: fromLonLat(transformed),
      ...(Number.isFinite(zoom) ? { zoom } : {}),
      duration: 520,
    })
  }

  /** 高亮选中的灾害点：传入 id 时放大其标记，传 null 恢复默认样式。 */
  function setSelectedHazard(id) {
    selectedHazardId = id == null ? null : String(id)
    hazardLayer.layer.changed()
  }

  function fitItems(items = []) {
    const features = items
      .filter((item) => item.lon != null && item.lat != null)
      .map((item) => new Feature({ geometry: new Point(fromLonLat(applyDatum([Number(item.lon), Number(item.lat)], pointDatum))) }))
    if (!features.length) {return}
    const merged = features.map((feature) => feature.getGeometry().getExtent()).reduce((acc, next, index) => {
      if (index === 0) {return next.slice()}
      acc[0] = Math.min(acc[0], next[0])
      acc[1] = Math.min(acc[1], next[1])
      acc[2] = Math.max(acc[2], next[2])
      acc[3] = Math.max(acc[3], next[3])
      return acc
    }, [])
    map.getView().fit(merged, { padding: [48, 48, 48, 48], maxZoom: 15, duration: 180 })
  }

  function setHazards(items = []) {
    itemRegistry.hazards = items
    fillPointFeatures(hazardLayer.source, items, pointDatum)
  }

  function setHazardsGeoJson(geojson) {
    setGeoJsonFeatures(hazardLayer.source, geojson)
  }

  function setDevices(items = []) {
    itemRegistry.devices = items
    fillPointFeatures(deviceLayer.source, items, pointDatum)
  }

  function setDevicesGeoJson(geojson) {
    setGeoJsonFeatures(deviceLayer.source, geojson)
  }

  function setPersonnel(items = []) {
    itemRegistry.personnel = items
    fillPointFeatures(personnelLayer.source, items, pointDatum)
    setPersonnelTrails(items)
  }

  function setPersonnelGeoJson(geojson) {
    setGeoJsonFeatures(personnelLayer.source, geojson)
  }

  function setWarnings(items = []) {
    itemRegistry.warnings = items
    fillPointFeatures(warningLayer.source, items, pointDatum)
  }

  function setWarningsGeoJson(geojson) {
    setGeoJsonFeatures(warningLayer.source, geojson)
  }

  function setPersonnelTrails(items = []) {
    itemRegistry.personnelTrails = items
    personnelTrailLayer.getSource().clear()
    items.forEach((item) => {
      if (!Array.isArray(item.route) || item.route.length < 2) {return}
      const coordinates = item.route.map((step) => fromLonLat(applyDatum([Number(step.lon), Number(step.lat)], pointDatum)))
      const trail = new Feature({ geometry: new LineString(coordinates) })
      trail.set('kind', 'personnel-trail')
      trail.set('name', item.name)
      personnelTrailLayer.getSource().addFeature(trail)
    })
  }

  /**
   * v1.7.2：业务图层（hazard/device/warning/personnel/trail）的可见性切换。
   * 图层默认全部可见；UI 上"图层"弹窗里关掉某类后只是隐藏 cluster 图层，源数据保留，
   * 重新打开时无需重新拉数据。
   * @param {'hazard'|'device'|'warning'|'personnel'|'trail'} kind
   * @param {boolean} visible
   * @returns {boolean} 实际生效的可见性
   */
  function setLayerVisible(kind, visible) {
    const layerMap = {
      hazard: hazardLayer.layer,
      device: deviceLayer.layer,
      warning: warningLayer.layer,
      personnel: personnelLayer.layer,
      trail: personnelTrailLayer,
    }
    const layer = layerMap[kind]
    if (!layer) {return false}
    layer.setVisible(!!visible)
    return layer.getVisible()
  }

  /** 读取业务图层的可见性，用于"图层"弹窗初始化 checkbox 状态。 */
  function getLayerVisible(kind) {
    const layerMap = {
      hazard: hazardLayer.layer,
      device: deviceLayer.layer,
      warning: warningLayer.layer,
      personnel: personnelLayer.layer,
      trail: personnelTrailLayer,
    }
    const layer = layerMap[kind]
    if (!layer) {return false}
    return layer.getVisible()
  }

  /**
   * 把 GPS 精度米数映射成地图缩放级别。
   * 原则：accuracy 圈要被完整看到，不能放大到圈外看不见。
   *   ±< 50 m    → zoom 16  小区级（GPS 精确）
   *   ±50–200 m  → zoom 14  街区级
   *   ±200–1000 m → zoom 12 区域级
   *   ±1–5 km   → zoom 10 城市级
   *   ±>5 km    → zoom 8  省份级（IP/WiFi 兜底）
   * 调用方传入的 `options.zoom` 是用户期望的"理想精度"，仅在精度优于该等级时才生效。
   *
   * 实现已上移到模块顶层（pickZoomForAccuracy 导出），避免重复定义。
   */

  /**
   * 把精度米数分档，返回 { grade, label, color } 供 UI 上色与文案用。
   * grade: 0 精确 / 1 良好 / 2 粗略 / 3 仅参考。
   *
   * 实现已上移到模块顶层（gradeAccuracy 导出），避免重复定义。
   */

  /**
   * 拿一次定位快照（带超时保护）；返回 Promise<GeolocationPosition>。
   * 故意把 maximumAge 设为 0 —— 浏览器拿到的是"上次定位的结果"时通常就是用户报"位置不对"的主因。
   * timeout 设到 25s 是为了覆盖冷启动 GPS 在室内/低信号下需要 20s+ 才能锁星的情况。
   * PC 端没有真实 GPS 硬件时会立即拿到 WiFi/IP 粗略定位，几乎不阻塞。
   */
  function fetchOneFix(extra = {}) {
    return new Promise((resolve, reject) => {
      navigator.geolocation.getCurrentPosition(resolve, reject, {
        enableHighAccuracy: true,
        maximumAge: 0,
        timeout: 25_000,
        ...extra,
      })
    })
  }

  /**
   * 调用浏览器 Geolocation API 获取当前位置。
   * 返回 Promise：成功 resolve 为 { lon, lat, accuracy, timestamp, zoom, grade, source }（WGS-84，经纬度）；
   * 失败 reject 为 Error，含可向用户展示的中文 message。
   * 优化策略：
   *   1. 强制新鲜定位（maximumAge=0），避免上一次会话的"老位置"被复用作答。
   *   2. 精度 >500m 时启动 watchPosition 取最多 8 秒，挑精度最佳的一帧。
   *   3. 根据精度自适应缩放，确保 accuracy 圈可见。
   * 注意：调用方要在 UI 上向用户解释"为什么需要定位"，且页面需处于 HTTPS / localhost。
   */
  function locateUser(options = {}) {
    return new Promise((resolve, reject) => {
      if (typeof navigator === 'undefined' || !navigator.geolocation) {
        reject(new Error('当前环境不支持浏览器定位 API'))
        return
      }
      const fallbackZoom = Number(options.zoom) || 16
      const userOpts = {
        enableHighAccuracy: options.enableHighAccuracy ?? true,
        timeout: options.timeout ?? 25_000,
        maximumAge: 0,
      }

      const finish = (pos) => {
        const lon = pos.coords.longitude
        const lat = pos.coords.latitude
        const accuracy = Number.isFinite(pos.coords.accuracy) ? pos.coords.accuracy : 0
        // 把原始坐标按当前瓦片坐标系做变换：高德 (GCJ-02) 走 wgs84ToGcj02，否则保持原值
        const [projLon, projLat] = applyDatum([lon, lat], pointDatum)
        // 写入 WGS-84 缓存，切底图后由 setBaseMap 重投影
        itemRegistry.userLocation = {
          lon,
          lat,
          accuracy,
          timestamp: pos.timestamp ?? Date.now(),
        }
        userLocationSource.clear()
        const feature = new Feature({
          geometry: new Point(fromLonLat([projLon, projLat])),
        })
        feature.set('kind', 'user-location')
        feature.set('lon', lon)
        feature.set('lat', lat)
        feature.set('accuracy', accuracy)
        feature.set('timestamp', pos.timestamp ?? Date.now())
        userLocationSource.addFeature(feature)
        const zoom = pickZoomForAccuracy(accuracy, fallbackZoom)
        userLocationView.animate({
          center: fromLonLat([projLon, projLat]),
          zoom,
          duration: 480,
        })
        const grade = gradeAccuracy(accuracy)
        resolve({
          lon,
          lat,
          accuracy,
          timestamp: pos.timestamp ?? Date.now(),
          zoom,
          grade,
          source: pos,
        })
      }

      const fail = (err) => {
        const messages = {
          1: '定位权限被拒绝，请在浏览器设置中允许位置访问',
          2: '当前设备定位不可用（GPS/网络定位均失败）',
          3: '定位超时，请到信号良好的开阔地带重试',
        }
        reject(new Error(messages[err?.code] || err?.message || '定位失败，请稍后重试'))
      }

      // 先取一帧；若精度 > 500m（多发生在 PC/WiFi/室内），进入 watch 模式再观察 8s 选最佳帧
      fetchOneFix(userOpts)
        .then((first) => {
          const firstAccuracy = Number.isFinite(first.coords.accuracy) ? first.coords.accuracy : Infinity
          if (firstAccuracy <= 500) {
            finish(first)
            return
          }
          // 进入 watch 模式挑精度最佳的样本
          const samples = [{ pos: first, accuracy: firstAccuracy, ts: first.timestamp ?? Date.now() }]
          let watchId = null
          let watchDeadline = null
          let resolved = false
          const cleanup = () => {
            if (watchId != null && navigator.geolocation.clearWatch) {
              navigator.geolocation.clearWatch(watchId)
            }
            if (watchDeadline) {clearTimeout(watchDeadline)}
          }
          const pickBest = () => {
            if (resolved) {return}
            resolved = true
            cleanup()
            samples.sort((a, b) => a.accuracy - b.accuracy)
            finish(samples[0].pos)
          }
          watchId = navigator.geolocation.watchPosition(
            (pos) => {
              const acc = Number.isFinite(pos.coords.accuracy) ? pos.coords.accuracy : Infinity
              samples.push({ pos, accuracy: acc, ts: pos.timestamp ?? Date.now() })
              // 拿到一个 <100m 的样本就提前收摊；否则等 8s 看完整集
              if (acc <= 100 && acc < firstAccuracy) {
                pickBest()
              }
            },
            () => {
              // watch 阶段报错（权限撤销、设备异常等）—— 取当前最佳结果作为兜底
              pickBest()
            },
            userOpts,
          )
          watchDeadline = setTimeout(pickBest, 8_000)
        })
        .catch(fail)
    })
  }

  /** 清除当前定位点 + accuracy 圈。 */
  function clearUserLocation() {
    itemRegistry.userLocation = null
    userLocationSource.clear()
  }

  /**
   * 用本地缓存的经纬度直接在地图上画"我的位置"标记，不调用 navigator.geolocation。
   * 用于进入页面后从 localStorage 还原上次定位结果，避免每次切换都重新询问浏览器权限。
   *
   * @param {{lon:number, lat:number, accuracy?:number, zoom?:number, animate?:boolean}} opts
   * @returns {{lon,lat,accuracy,timestamp,zoom,grade}|null}  渲染成功时返回标准化结果；参数非法返回 null
   */
  function paintUserLocation(opts = {}) {
    const lon = Number(opts.lon)
    const lat = Number(opts.lat)
    if (!Number.isFinite(lon) || !Number.isFinite(lat)) {return null}
    const accuracy = Number.isFinite(opts.accuracy) ? opts.accuracy : 0
    const fallbackZoom = Number.isFinite(opts.zoom) ? opts.zoom : 16
    const animate = opts.animate !== false
    const [projLon, projLat] = applyDatum([lon, lat], pointDatum)
    // 写入 WGS-84 缓存，切底图后由 setBaseMap 重投影
    itemRegistry.userLocation = {
      lon,
      lat,
      accuracy,
      timestamp: Date.now(),
    }
    userLocationSource.clear()
    const feature = new Feature({
      geometry: new Point(fromLonLat([projLon, projLat])),
    })
    feature.set('kind', 'user-location')
    feature.set('lon', lon)
    feature.set('lat', lat)
    feature.set('accuracy', accuracy)
    feature.set('timestamp', Date.now())
    userLocationSource.addFeature(feature)
    const zoom = pickZoomForAccuracy(accuracy, fallbackZoom)
    if (animate) {
      userLocationView.animate({
        center: fromLonLat([projLon, projLat]),
        zoom,
        duration: 480,
      })
    }
    return {
      lon,
      lat,
      accuracy,
      timestamp: Date.now(),
      zoom,
      grade: gradeAccuracy(accuracy),
    }
  }

  /** 暴露最近一次定位的元数据（用于 UI 卡片展示）。 */
  function getUserLocation() {
    const feature = userLocationSource.getFeatures()[0]
    if (!feature) {return null}
    return {
      lon: feature.get('lon'),
      lat: feature.get('lat'),
      accuracy: feature.get('accuracy'),
      timestamp: feature.get('timestamp'),
    }
  }

  function destroy() {
    map.setTarget(undefined)
  }

  /**
   * 设置某个气象 overlay 的可见性。kind: satellite | radar | precip24h | snow24h | drought
   */
  function setWeatherOverlay(kind, visible) {
    const layer = weatherOverlayLayers[kind]
    if (!layer) {return}
    layer.setVisible(Boolean(visible))
    map.renderSync()
  }

  /**
   * 设置 overlay 透明度 (0-1)。
   */
  function setWeatherOverlayOpacity(kind, opacity) {
    const layer = weatherOverlayLayers[kind]
    if (!layer) {return}
    layer.setOpacity(Math.max(0, Math.min(1, Number(opacity) || 0)))
    map.renderSync()
  }

  /**
   * 刷新指定 overlay 的图源 URL。
   * loader 是 async () => { url, timestamp } 类型的函数；传 null 则还原占位图（隐藏）。
   */
  function refreshWeatherOverlay(kind, loader) {
    const layer = weatherOverlayLayers[kind]
    if (!layer) {return Promise.reject(new Error(`Unknown overlay: ${kind}`))}
    if (!loader) {
      const source = new ImageStaticSource({
        url: makeTransparentPlaceholderDataUrl(),
        imageExtent: WEATHER_EXTENTS[kind],
        projection: 'EPSG:4326',
        crossOrigin: undefined,
      })
      layer.setSource(source)
      layer.setVisible(false)
      map.renderSync()
      return Promise.resolve(null)
    }
    return Promise.resolve(loader()).then((payload) => {
      if (!payload || !payload.url) {return null}
      const source = new ImageStaticSource({
        url: payload.url,
        imageExtent: WEATHER_EXTENTS[kind],
        projection: 'EPSG:4326',
        crossOrigin: undefined,
      })
      layer.setSource(source)
      map.renderSync()
      return payload
    })
  }

  /** 当前所有 overlay 的可见性快照，供 UI 同步。 */
  function getWeatherOverlayStates() {
    const result = {}
    Object.keys(weatherOverlayLayers).forEach((kind) => {
      result[kind] = {
        visible: weatherOverlayLayers[kind].getVisible(),
        opacity: weatherOverlayLayers[kind].getOpacity(),
      }
    })
    return result
  }

  /**
   * 兼容别名：视图层（CommandScreenPage / RealTimeMapPage）历史上调用了这些名字，
   * 旧实现没有导出，静默失败导致「大屏点位不显示、缩放/图层按钮点了没反应」。
   * 这里统一补齐为真实实现，不再让 UI 出现死按钮。
   */
  function zoomIn() {
    const view = map.getView()
    view.setZoom(Math.min((view.getZoom() || 0) + 1, view.getMaxZoom()))
  }
  function zoomOut() {
    const view = map.getView()
    view.setZoom(Math.max((view.getZoom() || 0) - 1, view.getMinZoom()))
  }
  function getItems() {
    return [
      ...itemRegistry.hazards,
      ...itemRegistry.devices,
      ...itemRegistry.warnings,
      ...itemRegistry.personnel,
    ].filter((item) => item && item.lon != null && item.lat != null)
  }
  function setGeoJSON(kind, geojson) {
    const key = String(kind || '').toLowerCase()
    if (key === 'hazard' || key === 'hazards') {setHazardsGeoJson(geojson)}
    else if (key === 'warning' || key === 'warnings') {setWarningsGeoJson(geojson)}
    else if (key === 'device' || key === 'devices') {setDevicesGeoJson(geojson)}
    else if (key === 'personnel') {setPersonnelGeoJson(geojson)}
    else {setGeoJsonFeatures(hazardLayer.source, geojson)}
  }
  function showWeatherOverlay(kind = 'satellite') {setWeatherOverlay(kind, true)}
  function hideWeatherOverlay() {
    Object.keys(weatherOverlayLayers).forEach((kind) => setWeatherOverlay(kind, false))
  }
  /** 订阅瓦片加载错误，返回取消订阅函数；UI 应据此显示可见报错而不是静默白屏。 */
  function onTileError(fn) {
    if (typeof fn !== 'function') {return () => {}}
    tileErrorListeners.add(fn)
    return () => tileErrorListeners.delete(fn)
  }
  function getBaseMapMeta() {
    const active = backupActive ? baseLayers.vector : baseLayers.satellite
    return {
      mode: currentMode,
      datum: pointDatum,
      backupActive,
      source: active.get('sourceMeta') || null,
      hasImageryTiles: true,
    }
  }

  return {
    map,
    destroy: () => {
      if (tileErrorTimer) {clearTimeout(tileErrorTimer)}
      tileErrorListeners.clear()
      destroy()
    },
    getMode: () => currentMode,
    getDatum: () => pointDatum,
    setMode: setBaseMap,
    zoomIn,
    zoomOut,
    getItems,
    setGeoJSON,
    showWeatherOverlay,
    hideWeatherOverlay,
    onTileError,
    getBaseMapMeta,
    setCenter,
    zoomToPoint,
    setSelectedHazard,
    setHazards,
    setHazardsGeoJson,
    setDevices,
    setDevicesGeoJson,
    setWarnings,
    setWarningsGeoJson,
    setPersonnel,
    setPersonnelGeoJson,
    setPersonnelTrails,
    setLayerVisible,
    getLayerVisible,
    fitItems,
    locateUser,
    paintUserLocation,
    clearUserLocation,
    getUserLocation,
    setWeatherOverlay,
    setWeatherOverlayOpacity,
    refreshWeatherOverlay,
    getWeatherOverlayStates,
    weatherOverlayLayers,
    baseLayers,
    layers: { hazardLayer, deviceLayer, warningLayer, personnelLayer, personnelTrailLayer },
    userLocation: { source: userLocationSource, layer: userLocationLayer },
    sentinel: {
      setOverlay: setSentinelOverlay,
      clearOverlay: clearSentinelOverlay,
      getMeta: getSentinelMeta,
      setSarOpacity: setSentinelSarOpacity,
      setS2Opacity: setSentinelS2Opacity,
      layers: baseLayers.sentinelLayers,
    },
  }
}

export async function createCesiumViewer(container, { center = [107.21, 30.61], point = null, sentinelTiles = null } = {}) {
  const Cesium = await import('cesium')
  // Cesium Ion 属境外商业影像服务，无合规资质且默认 token 必然失败，已彻底停用。
  // 3D 地球同样使用国内合规卫星影像瓦片金字塔。

  const overlay = ensureOverlay(container)
  overlay.style.display = 'block'
  overlay.style.visibility = 'visible'
  overlay.style.opacity = '1'

  let imageryProvider
  if (sentinelTiles && sentinelTiles.s2Url) {
    // 兼容：若调用方显式传入了自有影像 URL，仍按单张图渲染（不再是默认链路）
    imageryProvider = new Cesium.SingleTileImageryProvider({
      url: sentinelTiles.s2Url,
      rectangle: Cesium.Rectangle.fromDegrees(
        sentinelTiles.bbox?.[0] ?? center[0] - 0.2,
        sentinelTiles.bbox?.[1] ?? center[1] - 0.2,
        sentinelTiles.bbox?.[2] ?? center[0] + 0.2,
        sentinelTiles.bbox?.[3] ?? center[1] + 0.2,
      ),
      credit: mapAttribution,
    })
  } else {
    imageryProvider = new Cesium.UrlTemplateImageryProvider({
      url: buildAmapImageryUrl(6, 0),
      maximumLevel: 18,
      credit: new Cesium.Credit(mapAttribution),
    })
  }

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
    resolutionScale: resolvePixelRatio(),
    imageryProvider,
  })
  // 默认 maximumScreenSpaceError=2 会过早停止加载更精细瓦片；降到 1 让卫星纹理更锐利，
  // 代价是同时加载的瓦片更多，现代设备上一般无压力。
  viewer.scene.globe.maximumScreenSpaceError = 1

  viewer.scene.globe.depthTestAgainstTerrain = true
  viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString('#dfeaf3')
  viewer.scene.skyAtmosphere.show = true
  viewer.scene.backgroundColor = Cesium.Color.fromCssColorString('#dceef7')
  viewer.camera.setView({
    destination: Cesium.Cartesian3.fromDegrees(Number(center[0]), Number(center[1]), 6500),
    orientation: { heading: 0, pitch: Cesium.Math.toRadians(-38), roll: 0 },
  })

  if (point) {addCesiumPoint({ Cesium, viewer }, point)}
  viewer.resize()
  viewer.scene.requestRender()
  return { Cesium, viewer }
}

export function addCesiumPoint(viewerBundle, point) {
  if (!viewerBundle?.viewer || !point) {return}
  const { Cesium, viewer } = viewerBundle
  viewer.entities.removeAll()
  viewer.entities.add({
    position: Cesium.Cartesian3.fromDegrees(Number(point.lon), Number(point.lat), 30),
    point: {
      pixelSize: 14,
      color: Cesium.Color.fromCssColorString('#cd927e'),
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

export function buildClusterStyle(feature, type, selectedId) {
  const features = feature.get('features') || []
  const size = features.length
  if (size > 1) {
    const palette = {
      hazard: '#cd927e',
      device: '#4f7fd7',
      warning: '#e2a03a',
      personnel: '#87b096',
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

  const selected =
    selectedId != null &&
    item != null &&
    String(item.get?.('id')) === String(selectedId)

  if (type === 'hazard') {return buildHazardStyle(level, name, selected)}
  if (type === 'device') {return buildDeviceStyle(state, name)}
  if (type === 'warning') {return buildWarningStyle(level, name)}
  return buildPersonnelStyle(state, name)
}

export function buildWarningStyle(featureOrLevel = '预警') {
  const level = typeof featureOrLevel === 'string' ? featureOrLevel : featureOrLevel.get?.('level') || '预警'
  const color = level.includes('橙') || level.includes('红') ? '#cd927e' : level.includes('黄') ? '#c4a86e' : '#4f8f99'
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

export function buildHazardStyle(featureOrLevel = '正常', _name = '', selected = false) {
  const level = typeof featureOrLevel === 'string' ? featureOrLevel : featureOrLevel.get?.('level') || '正常'
  if (selected) {
    // 选中的风险点：更大半径 + 白色描边 + 品牌青色填充，放大后一眼可辨
    return new Style({
      image: new CircleStyle({
        radius: 13,
        fill: new Fill({ color: '#6f9e7f' }),
        stroke: new Stroke({ color: '#ffffff', width: 3 }),
      }),
      text: new Text({
        text: '!',
        fill: new Fill({ color: '#ffffff' }),
        stroke: new Stroke({ color: '#6f9e7f', width: 2 }),
        offsetY: -18,
        font: 'bold 13px sans-serif',
      }),
    })
  }
  const color = level.includes('高') ? '#cd927e' : level.includes('关注') ? '#c4a86e' : '#87b096'
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
  const color = state === '在线' ? '#6f9e7f' : '#d57b56'
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
  if (!person) {return ''}
  return [person.name, person.role, person.team, person.state, person.updatedAt].filter(Boolean).join(' · ')
}

/**
 * 人员轨迹线样式：折线 + 端点圆形。
 * 颜色按人员状态（在线=蓝、离线=粉）区分，弱化线条以避免与点位竞争视觉焦点。
 */
export function buildTrailStyle(feature) {
  if (!feature) {return undefined}
  const state = feature.get?.('state') || '在线'
  const color = state === '在线' ? '#4d78d7' : '#c95b87'
  return new Style({
    stroke: new Stroke({ color, width: 2.5, lineDash: [6, 4] }),
    image: new CircleStyle({
      radius: 4,
      fill: new Fill({ color }),
      stroke: new Stroke({ color: '#ffffff', width: 1.5 }),
    }),
  })
}

/**
 * GPS 定位点样式：中心实心点 + accuracy 圈的"双圆"叠层。
 * accuracy 用当前视图分辨率换算成像素，缩放地图时自动扩缩。
 */
export function buildUserLocationStyle(feature, resolution = 1) {
  const accuracy = Number(feature.get('accuracy')) || 0
  const accuracyPx = Number.isFinite(resolution) && resolution > 0 ? accuracy / resolution : 0
  return [
    // 准确性光晕（外圈，半透明）
    new Style({
      image: new CircleStyle({
        radius: Math.max(8, accuracyPx),
        fill: new Fill({ color: 'rgba(111, 158, 127, 0.12)' }),
        stroke: new Stroke({ color: 'rgba(111, 158, 127, 0.55)', width: 1.5 }),
      }),
    }),
    // 中心蓝点
    new Style({
      image: new CircleStyle({
        radius: 8,
        fill: new Fill({ color: '#6f9e7f' }),
        stroke: new Stroke({ color: '#ffffff', width: 3 }),
      }),
    }),
    // 定位图标上的感叹号装饰
    new Style({
      text: new Text({
        text: '⌖',
        fill: new Fill({ color: '#ffffff' }),
        font: 'bold 12px sans-serif',
        offsetY: 0,
      }),
    }),
  ]
}

/** 把 [lon, lat] 根据当前底图坐标系做必要变换：gcj02 时 wgs84 → gcj02。 */
function applyDatum([lon, lat], datum) {
  if (datum !== 'gcj02') {return [lon, lat]}
  return wgs84ToGcj02(lon, lat)
}

// 把 wgs84ToGcj02 / gcj02ToWgs84 也导出，便于搜索/地址解析模块统一处理坐标转换。
// 注意：这两个函数是纯计算，不依赖任何 DOM/网络，可安全在浏览器端直接调用。
export { wgs84ToGcj02, gcj02ToWgs84 }

function fillPointFeatures(source, items = [], datum = 'wgs84') {
  source.clear()
  items.forEach((item) => {
    if (item.lon == null || item.lat == null) {return}
    const [lon, lat] = applyDatum([Number(item.lon), Number(item.lat)], datum)
    source.addFeature(new Feature({ geometry: new Point(fromLonLat([lon, lat])), ...item }))
  })
}

function setGeoJsonFeatures(source, geojson) {
  const format = new GeoJSON()
  source.clear()
  if (!geojson) {return}
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
