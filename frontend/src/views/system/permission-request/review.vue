<template>
  <div class="pr-page">
    <!-- ================= 16.4.3 双人复核台账 ================= -->
    <div class="toolbar">
      <el-button type="primary" @click="loadLedgerRows">刷新</el-button>
      <el-tag type="info">只读证据面：每笔窗口外申请的 CCB 三方会签记录（会签即双人复核）</el-tag>
    </div>
    <el-table :data="reviewRows" v-loading="revLoading" border stripe
              :row-key="(r) => r.id" >
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-table :data="row.ccbSigns || []" border size="small" style="margin: 8px 24px; width: calc(100% - 48px)">
            <el-table-column prop="nodeName" label="CCB 节点" width="220" />
            <el-table-column prop="signerName" label="签署人" width="130">
              <template #default="{ row: s }">{{ s.signerName || s.signerId || '—' }}</template>
            </el-table-column>
            <el-table-column label="结果" width="110">
              <template #default="{ row: s }">
                <el-tag :type="taskType(s.signStatus)" size="small">{{ taskLabel(s.signStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="opinion" label="审批意见" min-width="180">
              <template #default="{ row: s }">{{ s.opinion || '—' }}</template>
            </el-table-column>
            <el-table-column label="签署时间" width="170">
              <template #default="{ row: s }">{{ fmtTime(s.opTime) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </el-table-column>
      <el-table-column prop="targetUsername" label="被授权人" width="130" />
      <el-table-column label="目标角色" min-width="180">
        <template #default="{ row }">{{ fmtRoles(row.rolesJson) }}</template>
      </el-table-column>
      <el-table-column label="风险" width="90">
        <template #default="{ row }">
          <el-tag :type="riskType(row.riskLevel)" size="small">{{ riskLabel(row.riskLevel) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="170" :formatter="(_, r) => fmtTime(r.submitTime)" />
      <el-table-column label="最终结果" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="申请单" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetailById(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 申请详情抽屉（跨页共用） -->
    <RequestDetailDrawer />
  </div>
</template>

<script setup>
/**
 * 16.4.3 双人复核台账（拆分自原权限申请操作台）：CCB 三方会签只读证据面。
 * 公共数据与详情抽屉状态见 usePermissionRequest.js。
 */
import { ref, onMounted } from 'vue'
import * as api from '@/api/system/permission-request'
import { usePermissionRequest } from './usePermissionRequest'
import { fmtRoles, fmtTime, riskType, riskLabel, statusType, statusLabel, taskType, taskLabel } from './request-helpers'
import RequestDetailDrawer from './_parts/RequestDetailDrawer.vue'

const { loadCommon, openDetailById } = usePermissionRequest()

const reviewRows = ref([])
const revLoading = ref(false)

async function loadLedgerRows() {
  revLoading.value = true
  try {
    const { data } = await api.outWindowLedger()
    reviewRows.value = data || []
  } finally {
    revLoading.value = false
  }
}

onMounted(async () => {
  await loadCommon()
  loadLedgerRows()
})
</script>

<style scoped>
.pr-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
</style>
