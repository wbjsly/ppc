/**
 * i18n 加载器（spec i18n-localization，BR-5.7-04 动态切换）。
 * 后端表为源：登录后按用户 Locale 拉当前生效版本 JSON（GET /api/i18n/packs/{locale}/current），
 * 注入 vue-i18n；setLocale 响应式切换（vue-i18n 天然支持，500ms 内完成，保留组件状态）。
 * 加载失败整体回退 zh-CN 默认包（BR-5.7-06 / C-5.7-04）。
 */
import { createI18n } from 'vue-i18n'
import request from '@/utils/request'
import { startCollector } from './gapCollector'
import { recordRenderError } from './fallback'

export const LOCALES = [
  { value: 'zh-CN', label: '中文（简体）' },
  { value: 'en-US', label: 'English' },
  { value: 'ja-JP', label: '日本語' }
]
export const DEFAULT_LOCALE = 'zh-CN'

/** 内置兜底包：语言包加载失败时的核心文案（BR-5.7-06 整体回退） */
const BUILTIN = {
  'zh-CN': {
    'btn.query': '查询', 'btn.save': '保存', 'btn.create': '新增', 'btn.delete': '删除',
    'btn.submit': '提交', 'btn.approve': '审批', 'btn.close': '关闭', 'btn.confirm': '确认',
    'btn.cancel': '取消', 'status.draft': '草稿', 'status.reviewing': '审核中',
    'error.required': '必填项不能为空', 'error.loadFailed': '加载失败', 'error.noData': '暂无数据'
  },
  'en-US': {
    'btn.query': 'Search', 'btn.save': 'Save', 'btn.create': 'Create', 'btn.delete': 'Delete',
    'btn.submit': 'Submit', 'btn.approve': 'Approve', 'btn.close': 'Close', 'btn.confirm': 'Confirm',
    'btn.cancel': 'Cancel', 'status.draft': 'Draft', 'status.reviewing': 'In Review',
    'error.required': 'This field is required', 'error.loadFailed': 'Load failed', 'error.noData': 'No data'
  },
  'ja-JP': {
    'btn.query': '検索', 'btn.save': '保存', 'btn.create': '新規', 'btn.delete': '削除',
    'btn.submit': '提交', 'btn.approve': '承認', 'btn.close': '閉じる', 'btn.confirm': '確認',
    'btn.cancel': 'キャンセル', 'status.draft': '下書き', 'status.reviewing': '審査中',
    'error.required': '必須項目です', 'error.loadFailed': '読み込みに失敗しました', 'error.noData': 'データがありません'
  }
}

let i18n = null
let currentLocale = DEFAULT_LOCALE
let loadedPacks = {} // locale -> { messages, missingCount }

/** 从后端拉当前生效语言包（后端表为源，决策 2） */
async function fetchPack(locale) {
  const r = await request.get(`/i18n/packs/${locale}/current`)
  const d = r.data || {}
  return { messages: d.messages || {}, missingCount: d.missingCount || 0, packNo: d.packNo }
}

/** 初始化 i18n（登录后/App 启动调用）；失败回退内置包（BR-5.7-06） */
export async function initI18n(locale) {
  const loc = locale || DEFAULT_LOCALE
  let messages = { [loc]: BUILTIN[loc] || BUILTIN[DEFAULT_LOCALE] }
  let missingCount = 0
  try {
    const pack = await fetchPack(loc)
    messages = { [loc]: { ...(BUILTIN[loc] || {}), ...pack.messages } }
    missingCount = pack.missingCount
  } catch (e) {
    recordRenderError(e) // i18n.render_error 埋点（BR-5.7-06）
    messages = { [loc]: BUILTIN[loc] || BUILTIN[DEFAULT_LOCALE] }
  }
  loadedPacks[loc] = { messages: messages[loc], missingCount }

  if (!i18n) {
    i18n = createI18n({
      legacy: false,
      globalInjection: true,
      locale: loc,
      fallbackLocale: DEFAULT_LOCALE,
      messages
    })
  } else {
    i18n.global.locale.value = loc
    i18n.global.setLocaleMessage(loc, messages[loc])
  }
  currentLocale = loc
  startCollector() // 启动缺口批量回传（BR-5.7-05）
  return i18n
}

/** 获取 i18n 实例（未初始化则同步建内置包兜底） */
export function getI18n() {
  if (!i18n) {
    i18n = createI18n({
      legacy: false,
      globalInjection: true,
      locale: DEFAULT_LOCALE,
      fallbackLocale: DEFAULT_LOCALE,
      messages: { [DEFAULT_LOCALE]: BUILTIN[DEFAULT_LOCALE] }
    })
  }
  return i18n
}

/**
 * 切换语种（BR-5.7-04：500ms 内完成，无需重启/重登，保留页面状态）。
 * vue-i18n 响应式 locale 切换 + 按需加载目标语包。
 */
export async function setLocale(locale) {
  if (!locale || locale === currentLocale) return
  let messages = BUILTIN[locale] || BUILTIN[DEFAULT_LOCALE]
  try {
    const pack = await fetchPack(locale)
    messages = { ...(BUILTIN[locale] || {}), ...pack.messages }
    loadedPacks[locale] = { messages: pack.messages, missingCount: pack.missingCount }
  } catch (e) {
    recordRenderError(e)
  }
  const inst = getI18n()
  inst.global.setLocaleMessage(locale, messages)
  inst.global.locale.value = locale
  currentLocale = locale
}

export function getCurrentLocale() {
  return currentLocale
}

/** 语言包缺失键统计（17.6.2 缺失回退演示用） */
export function getMissingCount(locale) {
  const p = loadedPacks[locale || currentLocale]
  return p ? p.missingCount : 0
}
