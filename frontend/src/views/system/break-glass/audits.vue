<template>
  <div class="bg-page">
    <!-- ================= 16.5.3 事后审计 ================= -->
    <div class="toolbar">
      <el-button type="primary" @click="loadAudits">刷新</el-button>
      <el-button type="warning" plain @click="doSweep">逾期扫描（24h 未签每 12h 提醒）</el-button>
    </div>
    <el-table :data="audits" v-loading="audLoading" border stripe>
      <el-table-column prop="targetUsername" label="被授权人" width="120" />
      <el-table-column label="生效 → 回收" min-width="200">
        <template #default="{ row }">{{ fmtTime(row.grantAt) }} → {{ fmtTime(row.reclaimAt) }}</template>
      </el-table-column>
      <el-table-column prop="logCount" label="期间操作数" width="100" align="center" />
      <el-table-column label="结论" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.conclusion === 'NO_VIOLATION'" type="success" size="small">无越权</el-tag>
          <el-tag v-else-if="row.conclusion === 'VIOLATION'" type="danger" size="small">有越权待查</el-tag>
          <el-tag v-else type="info" size="small">未签署</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="签署" min-width="140">
        <template #default="{ row }">
          <span v-if="row.signedAt">{{ row.signedByName || row.signedBy }} {{ fmtTime(row.signedAt) }}</span>
          <template v-else>
            <el-button link type="success" @click="sign(row, 'NO_VIOLATION')">无越权</el-button>
            <el-button link type="danger" @click="sign(row, 'VIOLATION')">有越权</el-button>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="专项评审" width="90" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.specialReview === '1'" type="danger" size="small">月度≥3</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 16.5.3 事后审计（拆分自原 Break-Glass 操作台）：审计报告台账 / 结论签署 / 逾期扫描。
 * 审计数据为本页独有。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/break-glass'
import { fmtTime } from './bg-helpers'

const audits = ref([])
const audLoading = ref(false)

async function loadAudits() {
  audLoading.value = true
  try {
    const { data } = await api.auditList()
    audits.value = data || []
  } finally {
    audLoading.value = false
  }
}

async function sign(row, conclusion) {
  const label = conclusion === 'NO_VIOLATION' ? '无越权' : '有越权待查'
  await ElMessageBox.confirm(`确认签署结论「${label}」？签署后不可更改。`, '签署审计结论',
    { type: conclusion === 'NO_VIOLATION' ? 'success' : 'warning' })
  await api.signAudit(row.id, conclusion)
  ElMessage.success('已签署')
  loadAudits()
}

async function doSweep() {
  const { data } = await api.sweepAuditOverdue()
  ElMessage.success(`扫描完成，本次提醒 ${data.reminded} 条`)
  loadAudits()
}

onMounted(loadAudits)
</script>

<style scoped>
.bg-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
</style>
