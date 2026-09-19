/**
 * 坐标系转换工具（WGS-84 ↔ GCJ-02）
 *
 * 中国境内使用的地图底图（高德、腾讯、百度）出于国家安全要求做了火星坐标偏移，
 * 国际通用 WGS-84 在中国境内会被系统性偏移 50–500m。本模块提供纯函数的双向转换，
 * 供业务模块（点位渲染、地址搜索）统一处理坐标系。
 *
 * 重要：本模块是纯计算，不依赖任何 DOM / 网络 / 第三方库，可在浏览器与服务端同源运行。
 */

/** 中国境内粗略边界：超出此范围不做偏移（境外仍是 WGS-84）。 */
const CHINA_BBOX = {
  lonMin: 72.004,
  lonMax: 137.8347,
  latMin: 0.8293,
  latMax: 55.8271,
}

function outOfChina(lon, lat) {
  return (
    lon < CHINA_BBOX.lonMin ||
    lon > CHINA_BBOX.lonMax ||
    lat < CHINA_BBOX.latMin ||
    lat > CHINA_BBOX.latMax
  )
}

/**
 * WGS-84 → GCJ-02（火星坐标，正向）
 * 算法来自互联网公开的 GCJ-02 加密公式（国测局制式），与高德/腾讯 API 同源。
 */
export function wgs84ToGcj02(lon, lat) {
  if (outOfChina(lon, lat)) {return [lon, lat]}
  const a = 6378245.0
  const ee = 0.00669342162296594323
  const x = lon - 105.0
  const y = lat - 35.0
  let dlat =
    -100 +
    2 * x +
    3 * y +
    0.2 * y * y +
    0.1 * x * y +
    0.2 * Math.sqrt(Math.abs(x))
  dlat +=
    ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3
  dlat +=
    ((20 * Math.sin(y * Math.PI) + 40 * Math.sin((y / 3) * Math.PI)) * 2) / 3
  dlat +=
    ((160 * Math.sin((y / 12) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30)) *
      2) /
    3
  let dlon =
    300 +
    x +
    2 * y +
    0.1 * x * x +
    0.1 * x * y +
    0.1 * Math.sqrt(Math.abs(x))
  dlon +=
    ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3
  dlon +=
    ((20 * Math.sin(x * Math.PI) + 40 * Math.sin((x / 3) * Math.PI)) * 2) / 3
  dlon +=
    ((150 * Math.sin((x / 12) * Math.PI) + 300 * Math.sin((x / 30) * Math.PI)) *
      2) /
    3
  const radlat = (lat / 180) * Math.PI
  let magic = Math.sin(radlat)
  magic = 1 - ee * magic * magic
  const sqrtmagic = Math.sqrt(magic)
  dlat =
    (dlat * 180) /
    (((a * (1 - ee)) / (magic * sqrtmagic)) * Math.PI)
  // 经度方向的分母必须保持"cos(radlat) * a/sqrtmagic * PI"，
  // 注意：之前实现里多写了一个 "/180"，让分母缩到 1/180，
  // 偏移量被放大 180 倍（中国境内经度偏移会变成 70–100km，不是 500m）。
  dlon =
    (dlon * 180) /
    (Math.cos(radlat) * (a / sqrtmagic) * Math.PI)
  return [lon + dlon, lat + dlat]
}

/**
 * GCJ-02 → WGS-84（逆向，二分逼近）
 * 用于把 AMap 搜索返回的 GCJ-02 经纬度还原回系统存储的 WGS-84。
 * 收敛阈值 1e-7 度（约 1cm），通常 5–8 次迭代收敛。
 */
export function gcj02ToWgs84(lon, lat) {
  if (outOfChina(lon, lat)) {return [lon, lat]}
  let wLon = lon
  let wLat = lat
  for (let i = 0; i < 15; i += 1) {
    const [gLon, gLat] = wgs84ToGcj02(wLon, wLat)
    const dLon = lon - gLon
    const dLat = lat - gLat
    if (Math.abs(dLon) < 1e-7 && Math.abs(dLat) < 1e-7) {return [wLon, wLat]}
    wLon += dLon
    wLat += dLat
  }
  return [wLon, wLat]
}

export { outOfChina }