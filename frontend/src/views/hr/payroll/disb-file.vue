<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">薪酬发放 · 代发文件（10.6.1）</span>
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
        <el-button type="primary" size="small" :disabled="!runId" @click="doGenerateFile">生成代发文件</el-button>
      </div>
      <el-table :data="files" height="260">
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
    </el-card>
  </div>
</template>

<script setup>
import PageInfo from '@/components/PageInfo.vue'
/**
 * 10.6.1 代发文件（拆分自原薪酬发放操作台）：生成代发文件 / 下载（网银上传）/ 作废。
 * 共享状态见 useDisbursement.js。
 */
import { ElMessage, ElMessageBox } from 'element-plus'
import { disbGenerateFile, disbVoidFile } from '@/api/hr/payroll'
import { useDisbursement } from './useDisbursement'

const { runId, files, board, loadAll } = useDisbursement()

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
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
