import { ref, computed } from 'vue'
import * as acct from '@/api/system/account'

/**
 * 16.1 账号管理四页共享状态（拆分自原账号管理操作台，design D2 composable 规则）。
 * 模块级单例：入职开通/在岗变更/离职回收/账号归档四页共享账号列表与加载，
 * 等价原「同一组件被四条路由复用」的行为。
 */
const loading = ref(false)
const allRows = ref([])

const activeRows = computed(() => allRows.value.filter(r => r.status === '1'))

async function load() {
  loading.value = true
  try {
    const res = await acct.accountPage({ size: 200 })
    allRows.value = res.data.records || []
  } finally { loading.value = false }
}

export function useAccounts() {
  return { loading, allRows, activeRows, load }
}
