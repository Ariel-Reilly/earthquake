/**
 * 会签与转派 API 封装。
 */
import { dashboardApi } from '../../dashboardApi.js'

export const confirmationSignService = {
  listByEvent: (eventId) => dashboardApi.listSignRecords(eventId),
  create: (payload) => dashboardApi.createSign(payload),
  confirm: ({ eventId, signId, signerId, opinion }) => dashboardApi.confirmSign({ eventId, signId, signerId, opinion }),
  reject:  ({ eventId, signId, signerId, opinion }) => dashboardApi.rejectSign({ eventId, signId, signerId, opinion }),
  transfer: ({ eventId, toUser, reason }) => dashboardApi.transferEvent({ eventId, toUser, reason }),
}

export default confirmationSignService
