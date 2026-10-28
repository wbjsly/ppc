// 12.7 服务监控操作台（add-service-monitoring-console）
// 内部三叶复用既有 sla 端点封装（零新内部端点）；门户服务等级为新增只读端点
export {
  collectSlaApi,
  getMetricsApi,
  getAlertsApi,
  respondAlertApi,
  getReportsApi,
  generateReportApi,
  reviewReportApi,
  archiveReportApi,
  publishReportApi
} from '@/api/proc/interface-integration'

import request from '@/utils/request'

/** 门户「服务等级」：我的已发布报告列表（仅 PUBLISHED） */
export function getPortalSlaReportsApi() {
  return request.get('/portal/sla-reports')
}

/** 报告详情（总览 + 仅本人章节，服务端过滤；无 partner 入参） */
export function getPortalSlaReportDetailApi(id) {
  return request.get(`/portal/sla-reports/${id}`)
}
