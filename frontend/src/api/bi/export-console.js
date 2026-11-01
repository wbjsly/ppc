import request from '@/utils/request'

/**
 * 导出控制台（14.5，add-export-console，spec bi-export-lifecycle /
 * bi-export-overlimit-approval）。
 */

// ---------- 14.5.1 导出申请 ----------

/** 提交导出（dataset=template 时 params 带 templateId/format/purpose） */
export function submitExportApi(body) {
  return request.post('/bi/export', body)
}

/** 我的导出任务 */
export function myExportTasksApi() {
  return request.get('/bi/export/tasks')
}

/** 待审批（ADMIN） */
export function pendingExportsApi() {
  return request.get('/bi/export/pending')
}

/** 已发布模板下拉（导出源） */
export function publishedTemplatesApi() {
  return request.get('/bi/report-templates', { params: { status: 'PUBLISHED' } })
}

/**
 * 下载导出文件（7 天有效，AES 解密流出）。
 * 走 blob 流：request 需 responseType blob。
 */
export function downloadExportApi(id) {
  return request.get(`/bi/export/${id}/download`, { responseType: 'blob' })
}

// ---------- 14.5.3 超量审批 ----------

/** 超量审批实例列表 */
export function overlimitApprovalsApi() {
  return request.get('/qms/approvals', { params: { bizType: 'BiExportOverLimit' } })
}
