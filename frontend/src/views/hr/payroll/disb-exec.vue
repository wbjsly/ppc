<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">薪酬发放 · 发放执行（10.6.2）</span>
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

      <div style="margin-bottom:8px">
        <el-button type="primary" size="small" :disabled="!activeFile" @click="openExec">登记发放结果</el-button>
      </div>
      <el-table :data="executions" height="260">
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
  </div>
</template>

<script setup>
/**
 * 10.6.2 发放执行（拆分自原薪酬发放操作台）：网银手工登记发放结果。
 * 共享状态见 useDisbursement.js。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { disbRegisterExec } from '@/api/hr/payroll'
import { useDisbursement } from './useDisbursement'

const { runId, files, executions, board, activeFile, loadAll } = useDisbursement()

const execVisible = ref(false)
const execForm = ref({ payDate: '', payAccount: '', paidTotal: 0, bankSerial: '', successCount: 0, failCount: 0, failDetail: '' })

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
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
