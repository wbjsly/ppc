<template>
  <div class="pr-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="出库复核（4.7.3）：行级三分支——通过 / 数量品种差异 / 外观异常"
      description="差异 → 任务挂 DIFF_PENDING 并锁过账（BR-4.4-29，闭环前 4.7.4 才能解锁）；外观异常 → 系统自动发起质量冻结申请（质量主管审批不省）+ 释放该批次预留（BR-4.4-30）。" />

    <div class="toolbar">
      <el-select v-model="taskId" placeholder="选择任务（拣货完成/差异待处理）" filterable
        style="width: 380px;" @change="loadDetail">
        <el-option v-for="t in tasks" :key="t.id"
          :label="`${t.taskNo} · ${t.srcDocNo} · ${taskText(t.status)}`" :value="t.id" />
      </el-select>
      <el-button @click="loadTasks">刷新</el-button>
      <el-button v-if="task && task.status === 'PICKED'" type="primary"
        @click="submitReview">送复核</el-button>
      <span v-if="task" class="state">状态：{{ taskText(task.status) }}</span>
    </div>

    <el-table :data="lines" size="small" border>
      <el-table-column prop="lineNo" label="行" width="50" />
      <el-table-column prop="itemCode" label="物料" width="140" />
      <el-table-column prop="batchNo" label="批次" width="130" />
      <el-table-column prop="binCode" label="仓位" width="100" />
      <el-table-column prop="qty" label="应拣" width="80" />
      <el-table-column prop="pickedQty" label="实拣" width="80" />
      <el-table-column label="复核结论" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.reviewResult" size="small"
            :type="row.reviewResult === 'PASS' ? 'success'
              : (row.reviewResult === 'QUALITY' ? 'danger' : 'warning')">
            {{ row.reviewResult }}
          </el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <template v-if="canWh && task && task.status === 'REVIEWING' && !row.reviewResult">
            <el-button link type="success" size="small"
              @click="review(row, 'PASS')">通过</el-button>
            <el-button link type="warning" size="small"
              @click="openDiff(row)">数量/品种差异</el-button>
            <el-button link type="danger" size="small"
              @click="openQuality(row)">外观异常</el-button>
          </template>
          <el-button v-if="canWh && task && task.status === 'DIFF_PENDING'
            && row.lineStatus === 'SHORT'" link type="primary" size="small"
            @click="doReturn(row)">退回补拣</el-button>
          <span v-if="row.reviewResult" class="done-mark">已复核</span>
        </template>
      </el-table-column>
    </el-table>

    <el-alert v-if="!lines.length" type="info" :closable="false" style="margin-top: 10px;"
      title="请先选择任务（任务须完成拣货，即 4.7.2 全行确认）" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPickTasksApi, getPickTaskDetailApi, transitionPickTaskApi,
  reviewPickLineApi, returnToPickApi
} from '@/api/inv/pick'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const roles = (userStore.userInfo && userStore.userInfo.roles) || []
const canWh = roles.includes('ROLE_ADMIN') || roles.includes('ROLE_WAREHOUSE')

const tasks = ref([])
const taskId = ref('')
const task = ref(null)
const lines = ref([])

const taskText = s => ({ CREATED: '待拣货', PICKING: '拣货中', PICKED: '拣货完成',
  REVIEWING: '复核中', DONE: '复核通过', DIFF_PENDING: '差异待处理',
  QUALITY_PENDING: '质量待处理', COMPLETED: '已完成', CANCELLED: '已作废' }[s] || s)

async function loadTasks() {
  const all = []
  for (const st of ['PICKED', 'REVIEWING', 'DIFF_PENDING', 'QUALITY_PENDING']) {
    const res = await getPickTasksApi({ current: 1, size: 30, status: st })
    all.push(...(res.data.records || []))
  }
  tasks.value = all
}

async function loadDetail() {
  if (!taskId.value) return
  const res = await getPickTaskDetailApi(taskId.value)
  task.value = res.data.task
  lines.value = res.data.lines || []
}

async function submitReview() {
  await transitionPickTaskApi(taskId.value, 'PICKED', 'REVIEWING')
  ElMessage.success('已送复核')
  await loadDetail()
  await loadTasks()
}

async function review(row, result) {
  await reviewPickLineApi({ taskId: taskId.value, lineNo: row.lineNo, result })
  ElMessage.success(result === 'PASS' ? `行 ${row.lineNo} 复核通过`
    : '已记录')
  await loadDetail()
}

async function openDiff(row) {
  const { value } = await ElMessageBox.prompt(
    `行 ${row.lineNo} 差异登记（格式：类型QTY/BATCH|复核实数|说明）`,
    '数量/品种差异', { type: 'warning',
      inputValue: `QTY|${row.pickedQty ?? ''}|复核发现数量差异` })
  const [kind, actual, ...rest] = String(value).split('|')
  const reason = rest.join('|').trim()
  if (!['QTY', 'BATCH'].includes(kind)) {
    ElMessage.error('类型仅支持 QTY/BATCH')
    return
  }
  await reviewPickLineApi({ taskId: taskId.value, lineNo: row.lineNo,
    result: 'DIFF', kind, reason: reason || undefined,
    expectQty: row.qty, actualQty: actual ? Number(actual) : undefined })
  ElMessage.success('差异已登记（任务挂差异待处理，过账已锁定，请到 4.7.4 闭环）')
  await loadDetail()
  await loadTasks()
}

async function openQuality(row) {
  const { value } = await ElMessageBox.prompt(
    `行 ${row.lineNo} 外观异常说明（将自动发起质量冻结申请并释放该批次预留）`,
    '外观异常（BR-4.4-30）', { type: 'warning', inputValue: '外包装破损' })
  if (!String(value).trim()) {
    ElMessage.error('异常说明必填')
    return
  }
  await reviewPickLineApi({ taskId: taskId.value, lineNo: row.lineNo,
    result: 'QUALITY', reason: String(value).trim() })
  ElMessage.success('已自动发起质量冻结申请（待质量主管审批）并释放该批次预留')
  await loadDetail()
  await loadTasks()
}

async function doReturn(row) {
  await returnToPickApi({ taskId: taskId.value, lineNo: row.lineNo })
  ElMessage.success('已退回补拣（行回待拣，差异保持待闭环）')
  await loadDetail()
  await loadTasks()
}

onMounted(loadTasks)
</script>

<style scoped>
.pr-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.state { font-size: 13px; color: #606266; }
.done-mark { font-size: 12px; color: #909399; }
</style>
