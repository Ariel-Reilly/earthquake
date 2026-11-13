const WEATHER_API_KEY = import.meta.env.VITE_WEATHER_API_KEY || ''
const WEATHER_API_URL = import.meta.env.VITE_WEATHER_API_URL || 'https://api.openweathermap.org/data/2.5/weather'
function describeWeather(code) {
  if (code === 0) {return '晴'}
  if (code === 1 || code === 2) {return '多云'}
  if (code === 3) {return '阴'}
  if (code === 45 || code === 48) {return '雾'}
  if (code >= 95) {return '雷暴'}
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) {return '降雪'}
  if (code >= 51 && code <= 82) {return '降雨'}
  return null
}

async function getOpenMeteoWeather({ lat, lon, location, signal }) {
  const url = new URL('https://api.open-meteo.com/v1/forecast')
  url.search = new URLSearchParams({
    latitude: lat,
    longitude: lon,
    current: 'temperature_2m,relative_humidity_2m,weather_code,surface_pressure,wind_speed_10m',
    hourly: 'precipitation_probability',
    forecast_hours: '2',
    wind_speed_unit: 'ms',
    timeformat: 'unixtime',
  }).toString()
  const response = await fetch(url, { signal })
  if (!response.ok) {throw new Error('天气服务暂不可用')}
  const { current, hourly } = await response.json()
  if (!Number.isFinite(current?.temperature_2m)) {throw new Error('该位置暂无天气数据')}
  const nextHour = hourly?.time?.findIndex((time) => time > current.time) ?? -1
  return {
    location,
    source: 'Open-Meteo',
    temperature: Math.round(current.temperature_2m),
    description: describeWeather(current.weather_code),
    humidity: current.relative_humidity_2m,
    precipitationProbability: hourly?.precipitation_probability?.[nextHour] ?? null,
    windSpeed: current.wind_speed_10m,
    pressure: current.surface_pressure,
    updatedAt: current.time ? new Date(current.time * 1000).toISOString() : null,
  }
}

export async function getRealtimeWeather({ lat, lon, location, signal } = {}) {
  if (WEATHER_API_KEY) {
    const url = new URL(WEATHER_API_URL)
    url.search = new URLSearchParams({
      lat, lon, appid: WEATHER_API_KEY, units: 'metric', lang: 'zh_cn',
    }).toString()
    try {
      const response = await fetch(url, { signal })
      const payload = response.ok ? await response.json() : null
      const temperature = payload?.main?.temp ?? payload?.temperature
      if (Number.isFinite(temperature)) {
        return {
          location,
          source: 'OpenWeather',
          temperature: Math.round(temperature),
          description: payload.weather?.[0]?.description ?? payload.description,
          humidity: payload.main?.humidity ?? payload.humidity,
          precipitationProbability: payload.pop != null ? Math.round(payload.pop * 100) : payload.precipitationProbability,
          windSpeed: payload.wind?.speed ?? payload.windSpeed,
          pressure: payload.main?.pressure ?? payload.pressure,
          updatedAt: payload.dt ? new Date(payload.dt * 1000).toISOString() : null,
        }
      }
    } catch (error) {
      if (signal?.aborted) {throw error}
    }
  }
  return getOpenMeteoWeather({ lat, lon, location, signal })
}

export function getWeatherAlertLevel(weather) {
  if (!weather) {return "未知"}
  const rain = Number(weather?.precipitationProbability ?? 0)
  const humidity = Number(weather?.humidity ?? 0)
  const temp = Number(weather.temperature ?? Infinity)
  if (rain >= 70 || humidity >= 88) {return '高'}
  if (rain >= 40 || humidity >= 75 || temp <= 0) {return '中'}
  return '低'
}
