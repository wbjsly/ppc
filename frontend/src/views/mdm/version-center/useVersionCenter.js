import { ref } from 'vue'
import { getEntitiesApi, getVersionsApi } from '@/api/mdm/version-sync'

/**
 * 1.7 版本中心三页共享状态（拆分自原版本中心操作台，design D2 composable 规则）。
 * 模块级单例：版本对比/历史回溯/版本回滚三页间切换保持类型、实体与版本数据，
 * 等价原「同一组件被三条路由复用」的行为。
 */
export const TYPES = [
  { value: 'ITEM', label: '物料' }, { value: 'CUSTOMER_GROUP', label: '客户组' },
  { value: 'CUSTOMER_VIEW', label: '客户视图' }, { value: 'SUPPLIER', label: '供应商' },
  { value: 'EXCHANGE_RATE', label: '汇率' }, { value: 'TAX_CODE', label: '税码' },
  { value: 'ORG_UNIT', label: '组织' }, { value: 'COST_CENTER', label: '成本中心' },
  { value: 'PROFIT_CENTER', label: '利润中心' }, { value: 'LEGAL_ENTITY', label: '法人' },
  { value: 'ITEM_CATEGORY', label: '物料分类' }, { value: 'PRICE_AGREEMENT', label: '价格协议' },
  { value: 'TAX_POLICY', label: '税策' }
]

const bizType = ref('ITEM')
const entityId = ref('')
const entities = ref([])
const entityLoading = ref(false)
const versions = ref([])
const fromVer = ref(null)
const toVer = ref(null)

async function searchEntities(q) {
  entityLoading.value = true
  try {
    const r = await getEntitiesApi(bizType.value, { keyword: q || '', page: 1, size: 30 })
    entities.value = r.data || []
  } finally {
    entityLoading.value = false
  }
}

function onTypeChange() {
  entityId.value = ''
  entities.value = []
  versions.value = []
  fromVer.value = null
  toVer.value = null
  searchEntities('')
}

async function loadVersions() {
  if (!entityId.value) return
  const r = await getVersionsApi(bizType.value, entityId.value)
  versions.value = r.data || []
  if (versions.value.length >= 2) {
    fromVer.value = versions.value[1].versionNo
    toVer.value = versions.value[0].versionNo
  } else if (versions.value.length === 1) {
    fromVer.value = versions.value[0].versionNo
    toVer.value = versions.value[0].versionNo
  }
}

export function useVersionCenter() {
  return { bizType, entityId, entities, entityLoading, versions, fromVer, toVer,
    searchEntities, onTypeChange, loadVersions }
}
