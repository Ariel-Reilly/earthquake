/**
 * 路径规划 API 封装。
 */
import { dashboardApi } from '../../dashboardApi.js'

export const routePlanningService = {
  getObstacles: () => dashboardApi.getObstacleAreas(),
  plan: (payload) => dashboardApi.planRoutes(payload),
  exportGeoJson: (route) => dashboardApi.exportRouteGeoJson(route),
}

export default routePlanningService
