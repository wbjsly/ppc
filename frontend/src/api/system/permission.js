import request from '@/utils/request'

// ---------- 角色权限（add-role-permission-console，16.2） ----------

/** 角色分页（含停用；keyword 模糊编码/名称） */
export function rolePage(params) {
  return request({ url: '/api/system/roles/page', method: 'get', params })
}

/** 全部启用角色（矩阵角色选择器 / 下拉） */
export function roleListAll() {
  return request({ url: '/api/system/roles', method: 'get' })
}

/** 新建角色（编码唯一；初始无菜单可见性） */
export function createRole(data) {
  return request({ url: '/api/system/roles', method: 'post', data })
}

/** 改名 / 排序 / 启停（status 0/1；ROLE_ADMIN 禁停用） */
export function updateRole(id, data) {
  return request({ url: `/api/system/roles/${id}`, method: 'put', data })
}

/** 删除角色（被用户绑定/菜单 PERM/矩阵覆盖引用 → 422 指明来源） */
export function deleteRole(id) {
  return request({ url: `/api/system/roles/${id}`, method: 'delete' })
}

/** 权限矩阵：角色 × 菜单合并视图（基线 ∪ override，含来源标注） */
export function matrix(role) {
  return request({ url: '/api/system/perm-matrix', method: 'get', params: { role } })
}

/** 勾选/取消某角色对某菜单的授权（仅落 override 差异格） */
export function setCell(roleCode, menuId, checked) {
  return request({ url: '/api/system/perm-matrix/cells', method: 'post', data: { roleCode, menuId, checked } })
}

/** 清除该角色全部矩阵覆盖（回退 099 基线口径） */
export function clearOverrides(roleCode) {
  return request({ url: `/api/system/perm-matrix/overrides/${roleCode}`, method: 'delete' })
}

/** 超基线授予例外申请单列表（PENDING/APPROVED/REJECTED） */
export function grantList(params) {
  return request({ url: '/api/system/role-grants', method: 'get', params })
}

/** 岗位基线列表（岗位 LEFT JOIN 基线，含违规角色标注） */
export function baselineList(params) {
  return request({ url: '/api/system/position-baselines', method: 'get', params })
}

/** 保存岗位基线（L1 特权校验 422） */
export function saveBaseline(posId, roleCodes) {
  return request({ url: `/api/system/position-baselines/${posId}`, method: 'put', data: { roleCodes } })
}
