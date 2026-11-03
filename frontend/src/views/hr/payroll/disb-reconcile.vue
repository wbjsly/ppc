<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">薪酬发放 · 回单对账（10.6.3）</span>
        <div>
          <el-input v-model="runId" placeholder="PayrollRun ID" clearable style="width:260px;margin-right:8px" @change="loadAll" />
          <el-button type="primary" @click="loadAll">查询</el-button>
        </div>
      </div>
      <PageInfo title="代发文件须批次 APPROVED；缺银行卡号阻断入文件；对账全部匹配且金额一致 → RECONCILED；差异 5 个工作日处理，金额调整 L2；对账完成+差异清零 → 归档锁定" style="margin-bottom:12px" />

      <el-descriptions v-if="board" :column="4" border style="margin-bottom:12px">
        <el-descriptions-item label="批次状态">{{ board.runStatus }}</el-descriptions-item>
        <el-descriptions-item label="回单数">{{ board.receiptCount }}</el-descriptions-item>
        <el-descriptions-item label="匹配率">{{ board.matchRate }}%</el-descriptions-item>
        <el-descriptions-item label="未处理差异">{{ board.openDiffs }} 条</el-descriptions-item>
      </el-descriptions>

      <div style="margin-bottom:8px">
        <el-button type="primary" size="small" :disabled="!runId" @click="openReceipt">登记回单</el-button>
        <el-button type="success" size="small" :disabled="!runId" @click="doReconcile">逐笔对账</el-button>
        <el-button type="warning" size="small" :disabled="!runId || board?.runStatus !== 'RECONCILED'" @click="doArchive">归档</el-button>
      </div>
      <el-table :data="diffs" height="260">
        <el-table-column prop="diffNo" label="差异单号" width="170" />
        <el-table-column prop="diffType" label="类型" width="140" />
        <el-table-column prop="expectAmount" label="应发" width="100" />
        <el-table-column prop="actualAmount" label="实收" width="100" />
        <el-table-column prop="deadline" label="处理时限" width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="{ OPEN: 'warning', CLOSED: 'success' }[row.status]">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <template v-if="row.status === 'OPEN' && row.resolvePlan">
              <el-button link type="success" size="small" @click="doApproveDiff(row, true)">L2 通过</el-button>
              <el-button link type="danger" size="small" @click="doApproveDiff(row, false)">驳回</el-button>
            </template>
            <el-button v-else-if="row.status === 'OPEN'" link type="primary" size="small"
                       @click="openResolve(row)">处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="receiptVisible" title="登记银行回单" width="400px">
      <el-form label-width="90px">
        <el-form-item label="回单号"><el-input v-model="receiptForm.receiptNo" /></el-form-item>
        <el-form-item label="回单日期"><el-date-picker v-model="receiptForm.receiptDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="金额"><el-input-number v-model="receiptForm.amount" :precision="2" :step="100" /></el-form-item>
        <el-form-item label="摘要"><el-input v-model="receiptForm.summary" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="receiptVisible = false">取消</el-button>
        <el-button type="primary" @click="doReceipt">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resolveVisible" title="差异处理" width="420px">
      <el-form label-width="90px">
        <el-form-item label="处理方案">
          <el-select v-model="resolveForm.resolvePlan" style="width:100%">
            <el-option label="退汇重发" value="RESEND" />
            <el-option label="下月补差" value="NEXT_MONTH" />
            <el-option label="手工发放" value="MANUAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input v-model="resolveForm.resolveNote" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" @click="doResolve">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 10.6.3 回单对账（拆分自原薪酬发放操作台）：登记回单 / 逐笔对账 / 差异处理与 L2 审批 / 归档锁定。
 * 共享状态见 useDisbursement.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { disbRegisterReceipt, disbReconcile, disbResolveDiff, disbApproveDiff, disbArchive } from '@/api/hr/payroll'
import { useDisbursement } from './useDisbursement'

const { runId, files, diffs, board, activeFile, loadAll } = useDisbursement()

const receiptVisible = ref(false)
const resolveVisible = ref(false)
const receiptForm = ref({ receiptNo: '', receiptDate: '', amount: 0, summary: '' })
const resolveForm = ref({ resolvePlan: 'RESEND', resolveNote: '' })
let resolvingId = ''

function openReceipt() {
  receiptForm.value = { receiptNo: 'RC' + Date.now(), receiptDate: new Date().toISOString().slice(0, 10), amount: Number(activeFile.value?.totalAmount || 0), summary: '工资代发回单' }
  receiptVisible.value = true
}
async function doReceipt() {
  try {
    await disbRegisterReceipt({ ...receiptForm.value, runId: runId.value })
    ElMessage.success('回单已登记'); receiptVisible.value = false; loadAll()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function doReconcile() {
  const { data } = await disbReconcile({ runId: runId.value })
  ElMessage[data.reconciled ? 'success' : 'warning'](
    data.reconciled ? '对账完成（全部匹配）' : `对账完成：匹配 ${data.matched}，未匹配 ${data.unmatched?.length || 0}`)
  loadAll()
}
function openResolve(row) { resolvingId = row.id; resolveForm.value = { resolvePlan: 'RESEND', resolveNote: '' }; resolveVisible.value = true }
async function doResolve() {
  try {
    await disbResolveDiff(resolvingId, resolveForm.value)
    ElMessage.success('处理方案已提交'); resolveVisible.value = false; loadAll()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function doApproveDiff(row, ok) {
  await disbApproveDiff(row.id, { approve: ok })
  ElMessage.success(ok ? 'L2 通过，差异关闭' : '已驳回'); loadAll()
}
async function doArchive() {
  await ElMessageBox.confirm('归档后凭证包锁定不可改，确认？', '归档', { type: 'warning' })
  await disbArchive({ runId: runId.value })
  ElMessage.success('已归档'); loadAll()
}
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
