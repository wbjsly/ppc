import { ref, computed } from 'vue'
import { useUserStore } from '@/store/user'

/**
 * 2.4 收货管理四页共享状态（拆分自原收货管理操作台，design D2 composable 规则）。
 * 模块级单例：角色判定与加载态在到货登记/待检管理/容差校验/入库过账四页间共享。
 */
const loading = ref(false)

export function useGoodsReceipt() {
  const userStore = useUserStore()
  const roles = computed(() => userStore.userInfo?.roles || [])
  const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
  const canCreate = computed(() => isAdmin.value || roles.value.includes('ROLE_RECEIVER'))
  const canPost = computed(() => isAdmin.value || roles.value.includes('ROLE_WAREHOUSE'))
  const canApproveAdj = computed(() => isAdmin.value || roles.value.includes('ROLE_PM'))
  return { loading, roles, isAdmin, canCreate, canPost, canApproveAdj }
}
