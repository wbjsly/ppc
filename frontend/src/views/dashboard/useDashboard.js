/**
 * 工作台数据层（add-configurable-workbench，spec personal-workbench）。
 * 模块级单例 composable：负责 /dash/layout/mine 的加载、个人层保存（全量 items +
 * excluded + shortcuts）、恢复默认；gridstack 的 DOM/位置交互在 index.vue（design D5：
 * 内容归 Vue、位置归 gridstack，位置序列化从 grid 产出后交本层提交）。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { useUserStore } from '@/store/user'
import { addableWidgets, visibleWidgets, widgetById } from './dash-registry'

const loading = ref(false)
const loadError = ref('')
const items = ref([])          // [{id,x,y,w,h}] 当前画布（服务端 merged 经角色过滤）
const excluded = ref([])       // 用户移除的 widget id（差量覆盖：不回补）
const shortcuts = ref([])      // 快捷入口（layout JSON 顶层附加字段）
const layersInfo = ref({})     // {global,role,user} 来源标注
const dirty = ref(false)       // 有未保存改动
const initialized = ref(false)

function userStore() { return useUserStore() }

/** 当前用户可见且已实现的 widget 集合（perm 预过滤——403 主闸）。 */
function visibleReady() {
  const roles = userStore().userInfo?.roles || []
  return addableWidgets(roles)
}

/** 全部可见项（含 planned，用于设置/选择列表灰显展示）。 */
function visibleAll() {
  const roles = userStore().userInfo?.roles || []
  return visibleWidgets(roles)
}

/**
 * 加载合并布局。目录过滤规则：
 * - 服务端 merged 里有、且目录可见 ready → 保留（服务端位置）
 * - 服务端有、但当前角色不可见或未实现 → 丢弃（模板含无权组件时本地抑制，不发任何请求）
 * - 服务端没有、目录 ready 且用户未移除 → 追加（GLOBAL 基线之外目录兜底，防新装环境空画布）
 */
async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await request({ url: '/dash/layout/mine', method: 'get' })
    const serverItems = res.data?.items || []
    excluded.value = res.data?.excluded || []
    shortcuts.value = res.data?.shortcuts || []
    layersInfo.value = res.data?.layers || {}

    const allow = new Map(visibleReady().map(w => [w.id, w]))
    const exSet = new Set(excluded.value)
    const out = []
    const seen = new Set()
    for (const it of serverItems) {
      if (!allow.has(it.id) || exSet.has(it.id)) continue
      out.push({ id: it.id, x: it.x, y: it.y, w: it.w, h: it.h })
      seen.add(it.id)
    }
    // 目录兜底：ready 且未被移除、服务端没发的组件，按默认尺寸补到画布尾部
    for (const [id, w] of allow) {
      if (!seen.has(id) && !exSet.has(id)) {
        out.push({ id, x: 0, y: 9999, w: w.w, h: w.h }) // y=9999 由 gridstack 自动排布
      }
    }
    items.value = out
    dirty.value = false
    initialized.value = true
  } catch (e) {
    loadError.value = e?.response?.status === 403 ? '无权限访问工作台布局' : '工作台布局加载失败'
  } finally {
    loading.value = false
  }
}

/** 提交保存：items 为 grid.save() 序列化的当前画布全量（design D2 保存语义）。 */
async function save(nextItems, nextShortcuts) {
  const payload = {
    v: 1,
    items: nextItems,
    excluded: excluded.value,
    shortcuts: nextShortcuts !== undefined ? nextShortcuts : shortcuts.value,
  }
  try {
    await request({ url: '/dash/layout/user', method: 'put', data: payload })
    items.value = nextItems
    if (nextShortcuts !== undefined) shortcuts.value = nextShortcuts
    dirty.value = false
    ElMessage.success('工作台布局已保存')
    return true
  } catch (e) {
    ElMessage.error(e?.response?.status === 403 ? '无权限保存' : '布局保存失败')
    return false
  }
}

/** 从画布移除：记 excluded（不回补）并置 dirty。 */
function removeWidget(id) {
  items.value = items.value.filter(i => i.id !== id)
  if (!excluded.value.includes(id)) excluded.value = [...excluded.value, id]
  dirty.value = true
}

/** 添加组件：剔除 excluded 标记（用户反悔了），返回新项由容器 makeWidget。 */
function addWidget(id) {
  const w = widgetById(id)
  if (!w || w.status !== 'ready') return null
  excluded.value = excluded.value.filter(x => x !== id)
  if (items.value.some(i => i.id === id)) return null
  const item = { id, x: 0, y: 9999, w: w.w, h: w.h }
  items.value = [...items.value, item]
  dirty.value = true
  return item
}

/** 恢复默认：清个人层（空 body），随后由调用方重新 load。 */
async function resetDefault() {
  try {
    await request({ url: '/dash/layout/user', method: 'put', data: '' })
    excluded.value = []
    shortcuts.value = []
    ElMessage.success('已恢复为角色模板默认布局')
    return true
  } catch {
    ElMessage.error('恢复默认失败')
    return false
  }
}

export function useDashboard() {
  return {
    loading, loadError, items, excluded, shortcuts, layersInfo, dirty, initialized,
    visibleReady, visibleAll, load, save, removeWidget, addWidget, resetDefault,
  }
}
