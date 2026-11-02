import { computed } from 'vue'
import { useUserStore } from '@/store/user'

/**
 * 7.2 折旧管理三页共享角色判定（拆分自原折旧管理操作台，design D2 composable 规则）。
 * 模块级引用 useUserStore（store 本身单例）。
 */
export function useDepRoles() {
  const userStore = useUserStore()
  const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
  const canRun = computed(() => myRoles.value.some(r => ['ROLE_ADMIN', 'ROLE_FIN_ASSET'].includes(r)))
  const canChange = computed(() => myRoles.value.some(r => ['ROLE_ADMIN', 'ROLE_ASSET_ADMIN'].includes(r)))
  const canApprove = computed(() => myRoles.value.some(r => ['ROLE_ADMIN', 'ROLE_FIN_ASSET'].includes(r)))
  return { myRoles, canRun, canChange, canApprove }
}
