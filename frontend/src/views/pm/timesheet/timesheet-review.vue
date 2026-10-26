<template>
  <div class="timesheet-review">
    <el-card>
      <template #header>
        <div class="card-header">
          <b>待审工时单</b>
          <el-button type="success" :disabled="!selection.length" @click="bulk">批量通过（{{ selection.length }}）</el-button>
        </div>
      </template>
      <el-table :data="rows" v-loading="loading" stripe @selection-change="s => selection = s">
        <el-table-column type="selection" width="45" />
        <el-table-column prop="workDate" label="日期" width="110" />
        <el-table-column prop="empName" label="员工" width="120" />
        <el-table-column prop="totalHours" label="合计(h)" width="90" />
        <el-table-column label="补录" width="70">
          <template #default="{ row }">{{ row.isBackfill === '1' ? '补录' : '—' }}</template>
        </el-table-column>
        <el-table-column label="明细" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            {{ (row.lines || []).map(l => `${l.wbsName || l.wbsId} ${l.hours}h`).join('；') }}
          </template>
        </el-table-column>
        <el-table-column prop="reviewerUserId" label="审核人" width="120" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="success" @click="review(row, 'APPROVE')">通过</el-button>
            <el-button link type="danger" @click="reject(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint">
        审核人规则：明细全部同一任务负责人 → 该负责人审；跨任务/无负责人 → 项目经理审。
        驳回必填原因；通过后自动按职级费率归集人工成本。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pendingReview, reviewTimesheet, bulkApprove } from '@/api/pm/timesheet'

const loading = ref(false)
const rows = ref([])
const selection = ref([])

onMounted(load)

async function load() {
  loading.value = true
  try {
    const { data } = await pendingReview()
    rows.value = data || []
  } finally {
    loading.value = false
  }
}

async function review(row, action, reason) {
  try {
    await reviewTimesheet(row.id, { action, reason })
    ElMessage.success(action === 'APPROVE' ? '已通过并归集' : '已驳回')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

async function reject(row) {
  let reason
  try {
    const { value } = await ElMessageBox.prompt('驳回原因（必填，至少 2 字）', '驳回', {
      inputPattern: /.{2,}/, inputErrorMessage: '至少 2 字'
    })
    reason = value
  } catch { return }
  await review(row, 'REJECT', reason)
}

async function bulk() {
  try {
    await ElMessageBox.confirm(`批量通过 ${selection.value.length} 张工时单？`, '确认', { type: 'warning' })
  } catch { return }
  try {
    const { data } = await bulkApprove(selection.value.map(r => r.id))
    ElMessage.success(`通过 ${data.approved} 张${data.failed?.length ? `，失败 ${data.failed.length} 张` : ''}`)
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.hint { color: #909399; font-size: 12px; margin-top: 10px; line-height: 1.7; }
</style>
