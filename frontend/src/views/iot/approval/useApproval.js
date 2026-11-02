import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  todoAggregateApi, todoHandleApi, todoScanEscalateApi, verifySignApi,
} from '@/api/iot/todo'

/**
 * 13.7 移动审批四页共享状态（拆分自原移动审批操作台，design D2 composable 规则）。
 * 模块级单例：待办聚合、双因子签审弹窗态与结果条在出入审批/异常审批/让步签审/
 * 消息待办四页间共享，等价原「同一组件被四条路由复用 + keep-alive」的行为；
 * 各页仅按自身 tab 键做过滤（filterTodos）。
 */
const TYPE_MAP = { inout: 'APPROVAL', exception: 'EXCEPTION', concession: 'APPROVAL',
  message: 'REPORT' }

const loading = ref(false)
const todos = ref([])
const result = ref(null)
const signDlg = ref(false)
const signing = ref(false)
const signPwd = ref('')
const signDevice = ref('PDA-WEB')
const signOpinion = ref('')
const signRow = ref(null)
const signPass = ref(true)
const webAuthn = typeof window !== 'undefined'
    && window.PublicKeyCredential !== undefined

function filterTodos(tabKey) {
  const t = TYPE_MAP[tabKey]
  return todos.value.filter(x => !t || x.todoType === t
      || (tabKey === 'concession' && x.refType === 'Concession')
      || (tabKey === 'message' && x.todoType === 'REPORT'))
}

async function load() {
  loading.value = true
  try {
    const r = await todoAggregateApi({ status: 'PENDING' })
    todos.value = r.data || []
  } finally {
    loading.value = false
  }
}

function openSign(row, pass) {
  signRow.value = row
  signPass.value = pass
  signOpinion.value = pass ? '同意' : '驳回'
  signPwd.value = ''
  signDlg.value = true
}

async function submitSign() {
  if (!signPwd.value) {
    ElMessage.error('签审须二次输入登录密码（双因子）')
    return
  }
  if (!signDevice.value) {
    ElMessage.error('须携带已注册终端码（C-4.11-18）')
    return
  }
  signing.value = true
  try {
    // 双因子第一因子：终端签审校验（注册态 + 密码）
    await verifySignApi({ deviceCode: signDevice.value, password: signPwd.value })
    // 处理待办并回写原单
    await todoHandleApi(signRow.value.id, {
      opinion: signOpinion.value,
      result: signPass.value ? 'APPROVE' : 'REJECT',
    })
    signDlg.value = false
    result.value = { type: 'success',
      title: signPass.value ? '签审通过' : '签审驳回',
      desc: `待办已处理并回写原单：${signRow.value.title}` }
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '签审失败')
  } finally {
    signing.value = false
  }
}

async function scanEscalate() {
  const r = await todoScanEscalateApi()
  ElMessage.info(`升级 ${(r.data?.details || []).length} 条：${(r.data?.details || []).join('、')}`)
  load()
}

function doSync() {
  ElMessage.info('联网状态下队列已实时同步')
}

export function useApproval() {
  return {
    loading, todos, result, signDlg, signing, signPwd, signDevice, signOpinion,
    webAuthn, filterTodos, load, openSign, submitSign, scanEscalate, doSync,
  }
}
