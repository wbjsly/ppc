<template>
  <div class="qms-approval">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">质量审批待办</span>
          <div>
            <el-button v-if="isAdmin" :loading="sweeping" @click="doSweep">触发超时扫描</el-button>
            <el-button :loading="loading" type="primary" @click="loadTodo">刷新待办</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="通用审批底座（C-4.12-01）：并行双签两节点全部通过才生效，缺一不可；任一驳回整单驳回。"
        description="超时口径：节点激活起 72 小时提醒一次、超 7 天自动升级（默认质量总监）；提醒/升级由 schedule 扫描驱动，也可手动触发（幂等）。" />

      <el-table :data="todo" v-loading="loading" stripe>
        <el-table-column prop="apprNo" label="审批单号" width="170">
          <template #default="{ row }"><b>{{ row.apprNo }}</b></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="bizTagType(row.bizType)">{{ bizTypeName(row.bizType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="nodeName" label="节点" width="140">
          <template #default="{ row }">
            {{ row.nodeName || row.roleRequired }}
          </template>
        </el-table-column>
        <el-table-column prop="applyBy" label="发起人" width="110" />
        <el-table-column label="等待时长" width="120">
          <template #default="{ row }">{{ waitText(row.applyDate) }}</template>
        </el-table-column>
        <el-table-column label="超时" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.kind === 'ESCALATE'" type="danger" size="small">已升级</el-tag>
            <template v-else>
              <el-tag v-if="remindHours(row) >= remindLimit" type="warning" size="small">已提醒{{ row.remindCount }}次</el-tag>
              <span v-else style="color: #c0c4cc;">—</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.kind === 'SIGN'">
              <el-button link type="success" @click="openPass(row)">通过</el-button>
              <el-button link type="danger" @click="openReject(row)">驳回</el-button>
            </template>
            <el-button link type="primary" @click="openLogs(row)">日志</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !todo.length" description="暂无待办（审批中的质量单据会出现在这里）" :image-size="90" />
    </el-card>

    <!-- 通过/驳回弹窗（意见必填校验见驳回） -->
    <el-dialog v-model="signVisible" :title="signTitle" width="480px">
      <el-form label-width="90px">
        <el-form-item label="审批意见">
          <el-input v-model="signOpinion" type="textarea" :rows="3"
                    :placeholder="signMode === 'reject' ? '驳回意见必填（至少 2 字）' : '同意（可空，默认：同意）'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="signVisible = false">取消</el-button>
        <el-button :loading="signing" :type="signMode === 'reject' ? 'danger' : 'success'" @click="submitSign">
          {{ signMode === 'reject' ? '确认驳回' : '确认通过' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 审批日志抽屉 -->
    <el-drawer v-model="logsVisible" :title="`审批日志：${logsTitle}`" size="560px">
      <template v-if="logsData.instance">
        <el-descriptions :column="1" border size="small" style="margin-bottom: 12px;">
          <el-descriptions-item label="审批单号">{{ logsData.instance.apprNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusTag(logsData.instance.status)">{{ statusName(logsData.instance.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="发起人">{{ logsData.instance.applyBy }}</el-descriptions-item>
          <el-descriptions-item label="发起时间">{{ logsData.instance.applyDate }}</el-descriptions-item>
          <el-descriptions-item label="办结时间">{{ logsData.instance.finishDate || '—' }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <el-timeline>
        <el-timeline-item v-for="(t, i) in (logsData.tasks || [])" :key="i"
                          :type="t.status === 'PASSED' ? 'success' : (t.status === 'REJECTED' ? 'danger' : 'primary')"
                          :timestamp="t.opTime || ''">
          <div><b>SEQ{{ t.seq }}</b> {{ t.nodeName || t.roleRequired }}
            <el-tag size="small" style="margin-left: 4px;"
                    :type="t.status === 'PASSED' ? 'success' : (t.status === 'REJECTED' ? 'danger' : 'info')">
              {{ taskStatusName(t.status) }}
            </el-tag>
            <el-tag v-if="t.escalated === '1'" size="small" type="danger" style="margin-left: 4px;">升级</el-tag>
          </div>
          <div v-if="t.signerName" style="color: #909399; font-size: 12px;">
            签署：{{ t.signerName }}<span v-if="t.opinion"> ｜ {{ t.opinion }}</span>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi,
  getQmsApprovalLogsApi, sweepQmsApprovalApi
} from '@/api/qms/approval'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
// 超时扫描为管理动作（幂等），仅 ADMIN 可见
const isAdmin = computed(() =>
  ((userStore.userInfo && userStore.userInfo.roles) || []).includes('ROLE_ADMIN'))

const remindLimit = 72 // approval-remind-hours

const loading = ref(false)
const sweeping = ref(false)
const todo = ref([])

const signVisible = ref(false)
const signing = ref(false)
const signMode = ref('pass')
const signOpinion = ref('')
const signRow = ref({})
const signTitle = computed(() => `${signMode.value === 'reject' ? '驳回' : '通过'}：${signRow.value.apprNo || ''}`)

const logsVisible = ref(false)
const logsTitle = ref('')
const logsData = ref({})

const BIZ_NAMES = {
  StandardPublish: '标准发布', Exempt: '免检申请', Concession: '让步接收',
  NcrDisposition: 'NCR 处置', Capa: 'CAPA', Scar: 'SCAR',
  CopqFinance: 'COPQ 财务确认', Return: '退货审批', StandardChange: '标准变更'
}

function bizTypeName(t) { return BIZ_NAMES[t] || t }
function bizTagType(t) {
  if (t === 'Concession') return 'warning'
  if (t === 'StandardPublish' || t === 'StandardChange') return 'primary'
  if (t === 'Scar' || t === 'CopqFinance') return 'danger'
  return 'info'
}
function statusName(s) {
  return { PENDING: '审批中', APPROVED: '已通过', REJECTED: '已驳回', CANCELLED: '已取消' }[s] || s
}
function statusTag(s) {
  return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELLED: 'info' }[s] || 'info'
}
function taskStatusName(s) {
  return { ACTIVE: '待签', PASSED: '已通过', REJECTED: '已驳回', SKIPPED: '跳过' }[s] || s
}
function waitText(applyDate) {
  if (!applyDate) return '—'
  const h = Math.floor((Date.now() - new Date(applyDate).getTime()) / 3600000)
  if (h < 1) return '<1 小时'
  if (h < 24) return `${h} 小时`
  return `${Math.floor(h / 24)} 天 ${h % 24} 小时`
}
function remindHours(row) {
  const base = row.applyDate ? new Date(row.applyDate).getTime() : Date.now()
  return Math.floor((Date.now() - base) / 3600000)
}

async function loadTodo() {
  loading.value = true
  try {
    const res = await getQmsApprovalTodoApi()
    todo.value = res.data || []
  } catch (e) {
    // 错误已由 request 拦截器提示
  } finally {
    loading.value = false
  }
}

async function doSweep() {
  sweeping.value = true
  try {
    const res = await sweepQmsApprovalApi()
    ElMessage.success(`扫描完成，处理 ${res.data?.handled ?? 0} 条`)
    await loadTodo()
  } catch (e) {
    // ignored
  } finally {
    sweeping.value = false
  }
}

function openPass(row) {
  signRow.value = row
  signMode.value = 'pass'
  signOpinion.value = ''
  signVisible.value = true
}
function openReject(row) {
  signRow.value = row
  signMode.value = 'reject'
  signOpinion.value = ''
  signVisible.value = true
}

async function submitSign() {
  const row = signRow.value
  if (signMode.value === 'reject' && (signOpinion.value || '').trim().length < 2) {
    ElMessage.warning('驳回意见必填（至少 2 字）')
    return
  }
  signing.value = true
  try {
    if (signMode.value === 'pass') {
      await passQmsApprovalApi(row.taskId, signOpinion.value)
      ElMessage.success('已通过')
    } else {
      await rejectQmsApprovalApi(row.taskId, signOpinion.value)
      ElMessage.success('已驳回')
    }
    signVisible.value = false
    await loadTodo()
  } catch (e) {
    // 错误已提示
  } finally {
    signing.value = false
  }
}

async function openLogs(row) {
  logsTitle.value = row.apprNo || ''
  try {
    const res = await getQmsApprovalLogsApi(row.bizType, row.bizId)
    logsData.value = res.data || {}
    logsVisible.value = true
  } catch (e) {
    // ignored
  }
}

onMounted(loadTodo)
</script>

<style scoped>
.qms-approval { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
