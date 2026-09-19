const WEATHER_API_KEY = import.meta.env.VITE_WEATHER_API_KEY || ''
const WEATHER_API_URL = import.meta.env.VITE_WEATHER_API_URL || 'https://api.openweathermap.org/data/2.5/weather'
const WEATHER_FALLBACK = {
  location: '龙川县',
  temperature: 26,
  description: '多云转小雨',
  humidity: 78,
  precipitationProbability: 40,
  windSpeed: 2.8,
  pressure: 1006,
  updatedAt: new Date().toISOString(),
}

function normalizeWeather(payload, fallbackLocation = '龙川县') {
  if (!payload) {return { ...WEATHER_FALLBACK, location: fallbackLocation }}
  const temp = payload.main?.temp ?? payload.temperature ?? WEATHER_FALLBACK.temperature
  return {
    location: payload.name || payload.location || fallbackLocation,
    temperature: Math.round(Number(temp)),
    description: payload.weather?.[0]?.description || payload.description || WEATHER_FALLBACK.description,
    humidity: payload.main?.humidity ?? payload.humidity ?? WEATHER_FALLBACK.humidity,
    precipitationProbability: payload.pop != null ? Math.round(payload.pop * 100) : payload.precipitationProbability ?? WEATHER_FALLBACK.precipitationProbability,
    windSpeed: payload.wind?.speed ?? payload.windSpeed ?? WEATHER_FALLBACK.windSpeed,
    pressure: payload.main?.pressure ?? payload.pressure ?? WEATHER_FALLBACK.pressure,
    updatedAt: payload.dt ? new Date(payload.dt * 1000).toISOString() : new Date().toISOString(),
  }
}

export async function getRealtimeWeather({ lat = 30.61, lon = 107.21, location = '龙川县' } = {}) {
  if (!WEATHER_API_KEY) {
    return { ...WEATHER_FALLBACK, location }
  }

  const url = new URL(WEATHER_API_URL)
  url.searchParams.set('lat', String(lat))
  url.searchParams.set('lon', String(lon))
  url.searchParams.set('appid', WEATHER_API_KEY)
  url.searchParams.set('units', 'metric')
  url.searchParams.set('lang', 'zh_cn')

  const response = await fetch(url.toString())
  if (!response.ok) {
    return { ...WEATHER_FALLBACK, location }
  }
  const payload = await response.json().catch(() => null)
  return normalizeWeather(payload, location)
}

export function getWeatherAlertLevel(weather) {
  const rain = Number(weather?.precipitationProbability ?? 0)
  const humidity = Number(weather?.humidity ?? 0)
  const temp = Number(weather?.temperature ?? 0)
  if (rain >= 70 || humidity >= 88) {return '高'}
  if (rain >= 40 || humidity >= 75 || temp <= 0) {return '中'}
  return '低'
}
