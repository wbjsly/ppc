import { ref } from 'vue'
import { getBankAccountsApi } from '@/api/fin/bank-account'

/**
 * 8.7.3/8.7.4 银行对账两页共享状态（拆分自原银行对账多 tab 页，design D2 composable 规则）。
 * 模块级单例：银行账户下拉与默认账户（差异处理、余额调节两页各自初始化时选中首个账户）。
 */

function currentPeriod() {
  const d = new Date()
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}`
}

const accounts = ref([])

async function loadAccounts() {
  const res = await getBankAccountsApi()
  accounts.value = res.data || []
}

export function useBankRecon() {
  return { currentPeriod, accounts, loadAccounts }
}
