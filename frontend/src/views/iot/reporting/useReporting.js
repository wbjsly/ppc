import { ref } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * 13.1 移动报工四页共享状态（拆分自原移动报工操作台，design D2 composable 规则）。
 * 模块级单例：工单选中态（current/faState/idemKey）、加载态与首件标签辅助、
 * 同步提示在工单接收/工序报工/质检确认/报工审核四页间共享，
 * 等价原「同一组件被四条路由复用 + keep-alive」的行为。
 */
const loading = ref(false)
const current = ref(null)
const faState = ref('NONE')
const idemKey = ref('')

function faType(s) {
  return { NONE: 'info', PENDING: 'warning', CONFIRMED: 'success', REJECTED: 'danger' }[s] || 'info'
}

function faText(s) {
  return { NONE: '未提交', PENDING: '待确认', CONFIRMED: '已通过', REJECTED: '已驳回' }[s] || s
}

function doSync() {
  ElMessage.info('联网状态下队列已实时同步')
}

export function useReporting() {
  return { loading, current, faState, idemKey, faType, faText, doSync }
}
