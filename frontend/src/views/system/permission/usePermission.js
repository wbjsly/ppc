import { ref } from 'vue'
import * as api from '@/api/system/permission'

/**
 * 16.2 权限管理三页共享状态（拆分自原权限管理操作台，design D2 composable 规则）。
 * 模块级单例：角色管理/权限矩阵/岗位基线三页共享角色全量列表（矩阵下拉、
 * 基线弹窗选项、新建角色后刷新），等价原「同一组件被三条路由复用」的行为。
 */
const matrixRoles = ref([])

async function loadMatrixRoles() {
  const { data } = await api.roleListAll()
  matrixRoles.value = data || []
}

export function usePermission() {
  return { matrixRoles, loadMatrixRoles }
}
