import { ref } from 'vue'
import { getDatasourcesApi } from '@/api/bi/data-extract'

/**
 * 14.2 数据抽取共享状态（拆分自原数据抽取操作台，design D2 composable 规则）。
 * 模块级单例：数据源列表（抽取配置页 CRUD，质量校验页规则表单选择所属数据源），
 * 等价原「同一组件被三条路由复用」的行为。
 */
const datasources = ref([])

async function loadDs() {
  const res = await getDatasourcesApi()
  datasources.value = res.data || []
}

export function useDataExtract() {
  return { datasources, loadDs }
}
