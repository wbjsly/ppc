import request from '@/utils/request'

// ================= 盘点工作台（7.5.1）=================
export const countPage = (params) => request.get('/eam/counts', { params })
export const countDetail = (id) => request.get(`/eam/counts/${id}`)
export const countCreate = (data) => request.post('/eam/counts', data)
export const countUpdate = (data) => request.put('/eam/counts', data)
export const countTeam = (id) => request.get(`/eam/counts/${id}/team`)
export const countMaintainTeam = (id, members) => request.post(`/eam/counts/${id}/team`, members)
export const countIssue = (id) => request.post(`/eam/counts/${id}/issue`)
export const countSamplePreview = () => request.get('/eam/counts/sample-preview')
export const countLinePage = (id, params) => request.get(`/eam/counts/${id}/lines`, { params })
export const countEntry = (id, params) => request.post(`/eam/counts/${id}/entry`, null, { params })
export const countImport = (id, csvContent) =>
  request.post(`/eam/counts/${id}/import`, csvContent, { headers: { 'Content-Type': 'text/plain' } })
export const countRefreshCoverage = (id) => request.post(`/eam/counts/${id}/coverage`)
export const countPostBaseChanges = (id) => request.get(`/eam/counts/${id}/post-base-changes`)
export const countSubmitReport = (id) => request.post(`/eam/counts/${id}/submit-report`)

// ================= 差异处理（7.5.2）=================
export const diffPage = (params) => request.get('/eam/count-diffs', { params })
export const diffDetail = (id) => request.get(`/eam/count-diffs/${id}`)
export const diffCompare = (planId) => request.post(`/eam/count-diffs/compare/${planId}`)
export const diffInvestigate = (id, params) =>
  request.post(`/eam/count-diffs/${id}/investigate`, null, { params })
export const diffSubmitApproval = (id) => request.post(`/eam/count-diffs/${id}/submit-approval`)
export const diffLiability = (id, params) =>
  request.post(`/eam/count-diffs/${id}/liability`, null, { params })
export const diffPostAdjust = (id) => request.post(`/eam/count-diffs/${id}/post-adjust`)
export const diffFreeze = (params) => request.post('/eam/count-diffs/freeze', null, { params })
export const diffFrozenCards = () => request.get('/eam/count-diffs/frozen')
export const diffCloseTagIssue = (id) => request.post(`/eam/count-diffs/${id}/close-tag-issue`)
