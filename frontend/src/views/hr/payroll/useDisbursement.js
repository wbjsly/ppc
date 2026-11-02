import { ref, computed } from 'vue'
import { disbFileList, disbExecList, disbDiffList, disbReconBoard } from '@/api/hr/payroll'

/**
 * 10.6 薪酬发放三页共享状态（拆分自原薪酬发放操作台，design D2 composable 规则）。
 * 模块级单例：代发文件/发放执行/回单对账三页间切换保持 runId 与数据，
 * 等价原「同一组件被三条路由复用」的行为。
 */
const runId = ref('')
const files = ref([])
const executions = ref([])
const diffs = ref([])
const board = ref(null)

const activeFile = computed(() => files.value.find(f => f.status === 'ACTIVE'))

async function loadAll() {
  if (!runId.value) return
  const [f, e, d, b] = await Promise.all([
    disbFileList({ runId: runId.value }),
    disbExecList(runId.value),
    disbDiffList({ runId: runId.value }),
    disbReconBoard(runId.value).catch(() => ({ data: null }))
  ])
  files.value = f.data || []
  executions.value = e.data || []
  diffs.value = d.data || []
  board.value = b.data
}

export function useDisbursement() {
  return { runId, files, executions, diffs, board, activeFile, loadAll }
}
