import request from '@/utils/request'

/**
 * 报表模板管理（14.1，add-report-template-console，spec report-template-management）。
 * 写操作后端 service 层 BI_DESIGNER 校验（403 兜底 ADMIN）；
 * 渲染 = PUBLISHED 任意登录角色 / 非 PUBLISHED 仅设计师。
 */

/** 模板列表（status/category/keyword 过滤；非设计师仅见 PUBLISHED） */
export function getTemplatesApi(params) {
  return request.get('/bi/report-templates', { params })
}

/** 模板详情（含生效版本与最新测试报告） */
export function getTemplateApi(id) {
  return request.get(`/bi/report-templates/${id}`)
}

/** 新建模板（SQL SELECT-only + 口径 diffCheck 硬阻断） */
export function createTemplateApi(row) {
  return request.post('/bi/report-templates', row)
}

/** 修改模板（元数据变更 → 测试报告作废强制重测） */
export function updateTemplateApi(id, row) {
  return request.put(`/bi/report-templates/${id}`, row)
}

/** 删除模板（PUBLISHED/审批中 422 拒） */
export function deleteTemplateApi(id) {
  return request.delete(`/bi/report-templates/${id}`)
}

/** 执行测试（三项报告：样本/性能/权限） */
export function runTestApi(id) {
  return request.post(`/bi/report-templates/${id}/test`)
}

/** 样本人工核对确认（三项合取刷新 PASS/FAIL） */
export function confirmAccuracyApi(testId) {
  return request.post(`/bi/report-templates/tests/${testId}/confirm-accuracy`)
}

/** 测试报告列表 */
export function getTestsApi(id) {
  return request.get(`/bi/report-templates/${id}/tests`)
}

/** 提交发布审批（前置：有效 PASS 报告 + 指标全部注册） */
export function submitTemplateApi(id) {
  return request.post(`/bi/report-templates/${id}/submit`)
}

/** 版本列表（版本号/口径版本/回退溯源） */
export function getVersionsApi(id) {
  return request.get(`/bi/report-templates/${id}/versions`)
}

/** 回退到历史版本（生成新版本号保留链） */
export function rollbackApi(versionId) {
  return request.post(`/bi/report-templates/versions/${versionId}/rollback`)
}

/** 渲染预览（分页表格数据；行级注入 + 掩码） */
export function renderApi(id, page, size) {
  return request.get(`/bi/report-templates/${id}/render`, { params: { page, size } })
}

/** 审批签署（复用既有审批台端点，详情抽屉就地签署） */
export function approvalPass(taskId, opinion) {
  return request({ url: '/api/qms/approvals/pass', method: 'post', data: { taskId, opinion } })
}

export function approvalReject(taskId, opinion) {
  return request({ url: '/api/qms/approvals/reject', method: 'post', data: { taskId, opinion } })
}
