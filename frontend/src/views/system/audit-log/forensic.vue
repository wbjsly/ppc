<template>
  <div class="app-container">
    <!-- ==================== 16.7.2 取证导出 ==================== -->
    <el-form :inline="true" size="small" @submit.prevent>
      <el-form-item label="调阅目的">
        <el-input v-model="applyForm.purpose" placeholder="≥10 字，说明取证事由" style="width:320px" />
      </el-form-item>
      <el-form-item label="时间范围">
        <el-date-picker v-model="applyForm.range" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="起" end-placeholder="止" style="width:340px" />
      </el-form-item>
      <el-form-item label="操作类型">
        <el-input v-model="applyForm.actionFilter" placeholder="FIELD_NAME 过滤（可空）" clearable style="width:160px" />
      </el-form-item>
      <el-form-item label="对象范围">
        <el-input v-model="applyForm.objectFilter" placeholder="EMP_ID 前缀（可空）" clearable style="width:140px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="submitApply">提交调阅申请</el-button>
      </el-form-item>
    </el-form>
    <el-alert type="info" :closable="false" style="margin-bottom:8px"
      title="流程：申请 → 内审（ROLE_UAR_AUDITOR）签署 → DPO（ROLE_DPO）签署 → 生成取证包（SHA-256 封条）→ 分片下载（7 天有效期）→ 使用完毕销毁。下载/销毁动作自身留痕。" />
    <el-table :data="requests" size="small" v-loading="reqLoading" border>
      <el-table-column prop="createDate" label="申请时间" width="150" />
      <el-table-column prop="purpose" label="目的" show-overflow-tooltip />
      <el-table-column label="时间范围" width="230">
        <template #default="{ row }">{{ row.rangeFrom }} ~ {{ row.rangeTo }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="mini" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内审签" width="80">
        <template #default="{ row }">{{ row.auditSignAt ? '✓' : '—' }}</template>
      </el-table-column>
      <el-table-column label="DPO 签" width="80">
        <template #default="{ row }">{{ row.dpoSignAt ? '✓' : '—' }}</template>
      </el-table-column>
      <el-table-column label="取证包" width="220">
        <template #default="{ row }">
          <template v-if="row.packageId">
            <span>{{ row.totalRows }} 条 / {{ row.shardCount }} 片</span>
            <el-tag size="mini" :type="row.packageStatus === 'GENERATED' ? 'success' : 'info'" style="margin:0 4px">
              {{ row.packageStatus === 'GENERATED' ? '可下载' : '已销毁' }}
            </el-tag>
            <el-link v-if="row.packageStatus === 'GENERATED'" type="primary"
              @click="downloadShards(row)">下载</el-link>
            <el-link v-if="row.packageStatus === 'GENERATED'" type="danger" style="margin-left:6px"
              @click="doConfirmUsed(row)">确认销毁</el-link>
          </template>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <template v-if="!row.auditSignAt && row.status === 'APPLYING'">
            <el-button size="mini" type="primary" @click="doSign(row, 'audit')">内审签</el-button>
          </template>
          <template v-if="row.auditSignAt && !row.dpoSignAt">
            <el-button size="mini" type="success" @click="doSign(row, 'dpo')">DPO 签</el-button>
          </template>
          <template v-if="row.dpoSignAt && !row.packageId">
            <el-button size="mini" type="warning" @click="doGenerate(row)">生成取证包</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <div style="margin-top:8px">
      <el-button size="small" type="danger" plain @click="doSweep">过期销毁扫描（7 天届满，幂等）</el-button>
    </div>
  </div>
</template>

<script setup>
/**
 * 16.7.2 取证导出（拆分自原审计日志操作台）：调阅申请 / 双签 / 取证包生成·下载·销毁。
 * 本页逻辑为单页独有，无跨页共享状态。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listRequests, applyRequest, signAudit, signDpo, generatePackage, downloadShard, confirmUsed, sweepExpired
} from '@/api/system/audit-log'

const applyForm = reactive({ purpose: '', range: null, actionFilter: '', objectFilter: '' })
const requests = ref([])
const reqLoading = ref(false)

const loadRequests = async () => {
  reqLoading.value = true
  try {
    const { data } = await listRequests()
    requests.value = data || []
  } finally {
    reqLoading.value = false
  }
}
const submitApply = async () => {
  if (!applyForm.purpose || applyForm.purpose.trim().length < 10) {
    ElMessage.warning('调阅目的不少于 10 字'); return
  }
  if (!applyForm.range || applyForm.range.length !== 2) {
    ElMessage.warning('请选择时间范围'); return
  }
  await applyRequest({
    purpose: applyForm.purpose, rangeFrom: applyForm.range[0], rangeTo: applyForm.range[1],
    actionFilter: applyForm.actionFilter || null, objectFilter: applyForm.objectFilter || null
  })
  ElMessage.success('调阅申请已提交，待内审签署')
  applyForm.purpose = ''; applyForm.range = null; applyForm.actionFilter = ''; applyForm.objectFilter = ''
  loadRequests()
}
const doSign = async (row, kind) => {
  try {
    const { value } = await ElMessageBox.prompt(
      kind === 'audit' ? '内审签署意见' : 'DPO 签署意见', '签署', { inputPattern: /.{2,}/, inputErrorMessage: '意见 ≥2 字' })
    if (kind === 'audit') await signAudit(row.id, value)
    else await signDpo(row.id, value)
    ElMessage.success('签署完成')
    loadRequests()
  } catch (e) { /* 取消 */ }
}
const doGenerate = async (row) => {
  await generatePackage(row.id)
  ElMessage.success('取证包已生成（封条 SHA-256 已预计算）')
  loadRequests()
}
const downloadShards = async (row) => {
  for (let s = 0; s < row.shardCount; s++) {
    const res = await downloadShard(row.packageId, s)
    const blob = new Blob([res], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `audit-forensic-${row.packageId}-shard${s}.csv`
    a.click()
    URL.revokeObjectURL(a.href)
  }
  ElMessage.success(`已下载 ${row.shardCount} 片（下载动作已留痕）`)
  loadRequests()
}
const doConfirmUsed = async (row) => {
  await ElMessageBox.confirm('确认使用完毕并销毁该取证包？销毁后不可恢复（操作留痕）。', '确认销毁', { type: 'warning' })
  await confirmUsed(row.packageId)
  ElMessage.success('已销毁')
  loadRequests()
}
const doSweep = async () => {
  const { data } = await sweepExpired()
  ElMessage.success(`过期扫描完成：销毁 ${data.destroyed} 个`)
  loadRequests()
}
const statusType = (s) => ({ APPLYING: 'info', READY: 'warning', PACKAGED: 'success', REJECTED: 'danger' }[s] || 'info')
const statusText = (s) => ({ APPLYING: '待签', READY: '双签齐', PACKAGED: '已出包', REJECTED: '已驳回' }[s] || s)

onMounted(loadRequests)
</script>
