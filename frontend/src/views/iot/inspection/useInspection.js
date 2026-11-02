import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { inspectCheckinApi, inspectListApi } from '@/api/iot/inspect'

/**
 * 13.3 设备巡检四页共享状态（拆分自原设备巡检操作台，design D2 composable 规则）。
 * 模块级单例：任务列表（扫码签到依赖）、当前任务/照片/点检项（执行→拍照留证跨页依赖）、
 * 加载态、结果条与同步提示在巡检执行/点检记录/异常标记/拍照留证四页间共享，
 * 等价原「同一组件被四条路由复用 + keep-alive」的行为。
 */
const loading = ref(false)
const tasks = ref([])
const current = ref(null)
const photoUrl = ref('')
const items = ref([{ item: '', value: '', abnormal: false }])
const result = ref(null)

async function loadTasks() {
  loading.value = true
  try {
    const r = await inspectListApi({ status: 'OPEN' })
    tasks.value = r.data || []
  } finally {
    loading.value = false
  }
}

async function checkin(row, code) {
  try {
    await inspectCheckinApi(row.id, code || row.deviceNo)
    ElMessage.success('签到成功')
    loadTasks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '签到失败')
  }
}

function onScan(code) {
  // 扫码即签到当前首个待执行任务（扫码框 → 设备签到）
  const t = tasks.value.find(x => x.status === 'OPEN' && !x.checkinAt)
  if (!t) {
    ElMessage.warning('无待签到任务')
    return
  }
  checkin(t, code)
}

function doSync() {
  ElMessage.info('联网状态下队列已实时同步')
}

export function useInspection() {
  return {
    loading, tasks, current, photoUrl, items, result,
    loadTasks, checkin, onScan, doSync,
  }
}
