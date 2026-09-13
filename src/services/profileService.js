/**
 * 个人中心 API 封装。
 * 复用 dashboardApi 路径，对外暴露一组语义清晰的方法。
 */
import { dashboardApi } from './dashboardApi'

export const profileService = {
  getProfile: () => dashboardApi.getProfile(),
  updateProfile: (payload) => dashboardApi.updateProfile(payload),
  changePassword: (payload) => dashboardApi.changeMyPassword(payload),
  getPreference: () => dashboardApi.getUserPreference('u-admin'),
  updatePreference: (payload) => dashboardApi.updateUserPreference(payload),
  updateThemeLanguage: (payload) => dashboardApi.updateThemeLanguage(payload),
}

export default profileService
