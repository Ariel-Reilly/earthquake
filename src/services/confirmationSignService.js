/**
 * 会签与转派 API 封装。
 */
import { dashboardApi } from './dashboardApi'

export const confirmationSignService = {
  listByEvent: (eventId) => dashboardApi.listSignRecords(eventId),
  create: (payload) => dashboardApi.createSign(payload),
  confirm: ({ signId, signerId, opinion }) => dashboardApi.confirmSign({ signId, signerId, opinion }),
  reject:  ({ signId, signerId, opinion }) => dashboardApi.rejectSign({ signId, signerId, opinion }),
  transfer: ({ eventId, toUser, reason }) => dashboardApi.transferEvent({ eventId, toUser, reason }),
}

export default confirmationSignService
