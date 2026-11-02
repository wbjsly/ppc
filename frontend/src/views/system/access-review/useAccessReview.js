import { ref } from 'vue'
import * as api from '@/api/system/access-review'

/**
 * 16.6 存取复核四页共享状态（拆分自原存取复核操作台，design D2 composable 规则）。
 * 模块级单例：
 * - 季度任务列表（季度任务页展示 + 快照比对/回收执行下拉 + 各操作后刷新）
 * - 当前选中任务 itemTaskId（快照比对与回收执行共用同一选择器，原组件内即为同一 ref）
 * - 快照明细 items（季度任务「快照/判定」入口设置任务后加载，供快照比对页展示）
 * 等价原「同一组件被四条路由复用」的行为。
 */
const tasks = ref([])
const taskLoading = ref(false)
const itemTaskId = ref('')

async function loadTasks() {
  taskLoading.value = true
  try {
    const { data } = await api.uarTaskList()
    tasks.value = data || []
  } finally {
    taskLoading.value = false
  }
}

/** 挂载时加载任务并回落默认选中（等价原 onMounted 的默认 task 选择逻辑） */
async function ensureTasks() {
  await loadTasks()
  if (tasks.value.length && !itemTaskId.value) {
    itemTaskId.value = tasks.value[0].id
  }
}

// ---------- 快照明细（16.6.2；季度任务「快照/判定」入口共用） ----------
const items = ref([])
const itemLoading = ref(false)
const mineOnly = ref(false)

async function loadItems() {
  if (!itemTaskId.value) return
  itemLoading.value = true
  try {
    const { data } = await api.uarItemList(itemTaskId.value, mineOnly.value)
    items.value = data || []
  } finally {
    itemLoading.value = false
  }
}

export function useAccessReview() {
  return {
    tasks, taskLoading, itemTaskId, loadTasks, ensureTasks,
    items, itemLoading, mineOnly, loadItems
  }
}
