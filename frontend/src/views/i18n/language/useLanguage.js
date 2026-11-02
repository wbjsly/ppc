import { ref } from 'vue'
import { coverageApi } from '@/api/i18n/i18n'

/**
 * 17.1 语言管理两页共享状态（拆分自原语言管理页，design D2 composable 规则）。
 * 模块级单例：缺口报告页展示覆盖率；键值维护页导入译文后同步刷新覆盖率（原页内 doImport 调用 loadCoverage）。
 */
const covLocale = ref('en-US')
const coverage = ref(null)

async function loadCoverage() {
  try {
    const r = await coverageApi(covLocale.value)
    coverage.value = r.data
  } catch { coverage.value = null }
}

export function useLanguage() {
  return { covLocale, coverage, loadCoverage }
}
