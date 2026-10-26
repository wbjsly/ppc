<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">薪酬发放（10.6，代发→付款→对账→归档）</span>
        <div>
          <el-input v-model="runId" placeholder="PayrollRun ID" clearable style="width:260px;margin-right:8px" @change="loadAll" />
          <el-button type="primary" @click="loadAll">查询</el-button>
        </div>
      </div>
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                title="代发文件须批次 APPROVED；缺银行卡号阻断入文件；对账全部匹配且金额一致 → RECONCILED；差异 5 个工作日处理，金额调整 L2；对账完成+差异清零 → 归档锁定" />

      <el-descriptions v-if="board" :column="4" border style="margin-bottom:12px">
        <el-descriptions-item label="批次状态">{{ board.runStatus }}</el-descriptions-item>
        <el-descriptions-item label="回单数">{{ board.receiptCount }}</el-descriptions-item>
        <el-descriptions-item label="匹配率">{{ board.matchRate }}%</el-descriptions-item>
        <el-descriptions-item label="未处理差异">{{ board.openDiffs }} 条</el-descriptions-item>
      </el-descriptions>

      <el-tabs>
        <el-tab-item label="代发文件（10.6.1）">
          <div style="margin-bottom:8px">
            <el-button type="primary" size="small" :disabled="!runId" @click="doGenerateFile">生成代发文件</el-button>
          </div>
          <el-table :data="files" height="200">
            <el-table-column prop="fileNo" label="文件号" width="170" />
            <el-table-column prop="lineCount" label="笔数" width="70" />
            <el-table-column prop="totalAmount" label="合计" width="120" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="downloadFile(row)">下载（网银上传）</el-button>
                <el-button v-if="row.status === 'ACTIVE'" link type="danger" size="small" @click="doVoidFile(row)">作废</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-item>

        <el-tab-item label="发放执行（10.6.2）">
          <div style="margin-bottom:8px">
            <el-button type="primary" size="small" :disabled="!activeFile" @click="openExec">登记发放结果</el-button>
          </div>
          <el-table :data="executions" height="180">
            <el-table-column prop="payDate" label="付款日" width="110" />
            <el-table-column prop="payAccount" label="付款账户" width="140" />
            <el-table-column prop="paidTotal" label="实付合计" width="120" />
            <el-table-column prop="bankSerial" label="网银流水" width="140" />
            <el-table-column prop="successCount" label="成功" width="70" />
            <el-table-column prop="failCount" label="失败" width="70" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'PAID' ? 'success' : 'warning'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-item>

        <el-tab-item label="回单对账（10.6.3）">
          <div style="margin-bottom:8px">
            <el-button type="primary" size="small" :disabled="!runId" @click="openReceipt">登记回单</el-button>
            <el-button type="success" size="small" :disabled="!runId" @click="doReconcile">逐笔对账</el-button>
            <el-button type="warning" size="small" :disabled="!runId || board?.runStatus !== 'RECONCILED'" @click="doArchive">归档</el-button>
          </div>
          <el-table :data="diffs" height="180">
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
        </el-tab-item>
      </el-tabs>
    </el-card>

    <el-dialog v-model="execVisible" title="登记发放执行（网银手工登记）" width="440px">
      <el-form label-width="100px">
        <el-form-item label="付款日期"><el-date-picker v-model="execForm.payDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="付款账户"><el-input v-model="execForm.payAccount" /></el-form-item>
        <el-form-item label="实付合计"><el-input-number v-model="execForm.paidTotal" :precision="2" :step="100" /></el-form-item>
        <el-form-item label="网银流水"><el-input v-model="execForm.bankSerial" /></el-form-item>
        <el-form-item label="成功笔数"><el-input-number v-model="execForm.successCount" :min="0" /></el-form-item>
        <el-form-item label="失败笔数"><el-input-number v-model="execForm.failCount" :min="0" /></el-form-item>
        <el-form-item label="失败明细">
          <el-input v-model="execForm.failDetail" type="textarea" :rows="2" placeholder="格式：员工ID,金额;员工ID,金额" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="execVisible = false">取消</el-button>
        <el-button type="primary" @click="doExec">登记</el-button>
      </template>
    </el-dialog>

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
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { disbFileList, disbGenerateFile, disbVoidFile, disbRegisterExec, disbExecList,
  disbRegisterReceipt, disbReconcile, disbDiffList, disbResolveDiff, disbApproveDiff,
  disbArchive, disbReconBoard } from '@/api/hr/payroll'

const runId = ref('')
const files = ref([])
const executions = ref([])
const diffs = ref([])
const board = ref(null)
const execVisible = ref(false)
const receiptVisible = ref(false)
const resolveVisible = ref(false)
const execForm = ref({ payDate: '', payAccount: '', paidTotal: 0, bankSerial: '', successCount: 0, failCount: 0, failDetail: '' })
const receiptForm = ref({ receiptNo: '', receiptDate: '', amount: 0, summary: '' })
const resolveForm = ref({ resolvePlan: 'RESEND', resolveNote: '' })
let resolvingId = ''

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
async function doGenerateFile() {
  try {
    const { data } = await disbGenerateFile({ runId: runId.value })
    ElMessage.success(`代发文件 ${data.file?.fileNo} 已生成（${data.file?.lineCount} 笔）`)
    loadAll()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
function downloadFile(row) {
  const blob = new Blob([row.content || ''], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${row.fileNo}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
async function doVoidFile(row) {
  const { value } = await ElMessageBox.prompt('作废原因', '作废代发文件', { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
  await disbVoidFile(row.id, { reason: value })
  ElMessage.success('已作废'); loadAll()
}
function openExec() {
  const f = activeFile.value
  execForm.value = { payDate: new Date().toISOString().slice(0, 10), payAccount: '', paidTotal: Number(f?.totalAmount || 0), bankSerial: '', successCount: f?.lineCount || 0, failCount: 0, failDetail: '' }
  execVisible.value = true
}
async function doExec() {
  try {
    const { data } = await disbRegisterExec({ ...execForm.value, disbId: activeFile.value.id })
    ElMessage.success(`登记成功（${data.status}）`); execVisible.value = false; loadAll()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
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
  const { data } = await disbArchive({ runId: runId.value })
  ElMessage.success('已归档'); loadAll()
}
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
