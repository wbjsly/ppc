import { ref } from 'vue'
import { myExportTasksApi, overlimitApprovalsApi } from '@/api/bi/export-console'

/**
 * 14.5 导出控制台三页共享状态（拆分自原数据导出操作台，design D2 composable 规则）。
 * 模块级单例：导出任务（导出申请页展示 + 水印标记页水印明细）、超量审批单
 * （超量审批页展示，导出申请页提交超量导出后刷新），等价原「同一组件被三条路由复用」的行为。
 */
const tasks = ref([])
const approvals = ref([])

async function loadTasks() {
  try {
    const m = await myExportTasksApi()
    tasks.value = m.data || []
  } catch (e) { tasks.value = [] }
}

async function loadApprovals() {
  try {
    const a = await overlimitApprovalsApi()
    approvals.value = a.data || []
  } catch (e) { approvals.value = [] }
}

export function useExportConsole() {
  return { tasks, approvals, loadTasks, loadApprovals }
}
