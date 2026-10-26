import request from '@/utils/request'

// ---------- 职级（10.2.4） ----------
export function gradeList() {
  return request({ url: '/api/hr/org/grades', method: 'get' })
}

export function createGrade(data) {
  return request({ url: '/api/hr/org/grades', method: 'post', data })
}

export function updateGrade(id, data) {
  return request({ url: `/api/hr/org/grades/${id}`, method: 'put', data })
}

export function toggleGrade(id, status) {
  return request({ url: `/api/hr/org/grades/${id}/status`, method: 'put', params: { status } })
}

export function resolveBand(posId) {
  return request({ url: '/api/hr/org/band', method: 'get', params: { posId } })
}

// ---------- 部门升级（10.2.1） ----------
export function deptList() {
  return request({ url: '/api/hr/depts', method: 'get' })
}

export function createDept(data) {
  return request({ url: '/api/hr/depts', method: 'post', data })
}

export function deactivateDept(id) {
  return request({ url: `/api/hr/org/depts/${id}/deactivate`, method: 'put' })
}

export function setCostCenter(id, costCenter) {
  return request({ url: `/api/hr/org/depts/${id}/cost-center`, method: 'put', data: { costCenter } })
}

// ---------- 岗位（10.2.2） ----------
export function positionList(deptId) {
  return request({ url: '/api/hr/positions', method: 'get', params: { deptId } })
}

export function createPosition(data) {
  return request({ url: '/api/hr/positions', method: 'post', data })
}

export function updatePositionMeta(id, data) {
  return request({ url: `/api/hr/org/positions/${id}/meta`, method: 'put', data })
}

// ---------- 汇报关系（10.2.3） ----------
export function setReportTo(empId, data) {
  return request({ url: `/api/hr/org/reports/${empId}`, method: 'put', data })
}

export function reportAncestors(empId) {
  return request({ url: `/api/hr/org/reports/${empId}/ancestors`, method: 'get' })
}

export function reportSubordinates(empId) {
  return request({ url: `/api/hr/org/reports/${empId}/subordinates`, method: 'get' })
}

export function employeeList() {
  return request({ url: '/api/hr/employees', method: 'get' })
}

// ---------- 调整单 ----------
export function createMerge(data) {
  return request({ url: '/api/hr/org/changes/merge', method: 'post', data })
}

export function createManagerChange(data) {
  return request({ url: '/api/hr/org/changes/manager', method: 'post', data })
}

export function changeList(params) {
  return request({ url: '/api/hr/org/changes', method: 'get', params })
}
