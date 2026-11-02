/**
 * 缺失回退链（BR-5.7-05 / C-5.7-07）。
 * 渲染命中未翻译键 → 回退 zh-CN 源文案 + 视觉标记 class + 缺口埋点收集。
 */
import { recordMissing } from './gapCollector'

/** 视觉标记：浅灰虚下划线（S-5.7-05） */
export const MISSING_CLASS = 'i18n-missing'

/**
 * 回退取词：targetMap 有则用之；无则记缺口并回退 sourceMap。
 * 返回 { text, missing } —— missing=true 时渲染层加 MISSING_CLASS。
 */
export function tWithFallback(key, targetMap, sourceMap) {
  const tv = targetMap ? targetMap[key] : undefined
  if (tv !== undefined && tv !== null && tv !== '') {
    return { text: tv, missing: false }
  }
  // 缺口：记录埋点（批量回传）并回退源语
  recordMissing(key)
  const sv = sourceMap ? sourceMap[key] : undefined
  return { text: sv !== undefined && sv !== null && sv !== '' ? sv : key, missing: true }
}

/** 渲染异常回退（BR-5.7-06：语言包加载失败整体回退默认包） */
export function recordRenderError(err) {
  recordMissing('__render_error__:' + (err && err.message ? err.message : 'unknown'), 'P0')
}
