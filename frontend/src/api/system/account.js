import request from '@/utils/request'

// ---------- 账号运维（add-account-lifecycle-console，16.1） ----------

/** 账号分页（status: 1 启用 / 0 停用 / 2 归档；keyword 模糊 username/昵称） */
export function accountPage(params) {
  return request({ url: '/api/system/accounts', method: 'get', params })
}

/** 账号详情（角色清单 + 员工锚） */
export function accountDetail(id) {
  return request({ url: `/api/system/accounts/${id}`, method: 'get' })
}

/** 重置密码（随机新密码一次性返回 + 强制改密标记） */
export function resetPassword(id) {
  return request({ url: `/api/system/accounts/${id}/reset-password`, method: 'post' })
}

/** 角色全量调整（roleIds 数组，前后集合 diff 审计） */
export function adjustRoles(id, roleIds) {
  return request({ url: `/api/system/accounts/${id}/roles`, method: 'put', data: { roleIds } })
}

/** 人工建号兜底（对已有员工档案未建号者，幂等） */
export function manualCreate(empId) {
  return request({ url: '/api/system/accounts/manual-create', method: 'post', data: { empId } })
}

/** 强停（置 0 + 权限清单快照审计） */
export function disableAccount(id) {
  return request({ url: `/api/system/accounts/${id}/disable`, method: 'post' })
}

/** 归档（confirmUsername 二次确认；仅停用态；不可恢复） */
export function archiveAccount(id, confirmUsername) {
  return request({ url: `/api/system/accounts/${id}/archive`, method: 'post', data: { confirmUsername } })
}

/** 生命周期清扫手动触发（离职 24h 强停 + 停用 90 天归档） */
export function sweepAccounts() {
  return request({ url: '/api/system/accounts/sweep', method: 'post' })
}

/** 角色全集（角色调整下拉数据源） */
export function roleOptions() {
  return request({ url: '/api/system/roles', method: 'get' })
}
