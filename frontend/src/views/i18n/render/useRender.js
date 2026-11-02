import { ref, computed } from 'vue'
import { getCurrentLocale, getMissingCount } from '@/i18n'
import { getKeysApi } from '@/api/i18n/i18n'

/**
 * 17.6 界面渲染两页共享状态（拆分自原界面渲染页，design D2 composable 规则）。
 * 模块级单例：动态切换与缺失回退两页共用当前语种、键值数据、源语文案映射与加载函数。
 */
const currentLocale = ref(getCurrentLocale())
const keys = ref([])
const missingCount = ref(0)

const sourceMap = computed(() => {
  const m = {}
  keys.value.forEach(k => { m[k.keyName] = k.sourceText })
  return m
})

async function loadKeys() {
  try {
    const r = await getKeysApi({ locale: currentLocale.value === 'zh-CN' ? 'ja-JP' : currentLocale.value, size: 200 })
    keys.value = r.data || []
    missingCount.value = getMissingCount(currentLocale.value)
  } catch { keys.value = [] }
}

export function useRender() {
  return { currentLocale, keys, missingCount, sourceMap, loadKeys }
}
