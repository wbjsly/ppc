<template>
  <div class="pr-page">
    <!-- ================= 16.4.2 窗口外升级审批 ================= -->
    <div class="toolbar">
      <el-select v-model="owQuery.status" placeholder="状态" clearable style="width: 150px"
        @change="loadOutWindow">
        <el-option label="审批中" value="PENDING_APPROVAL" />
        <el-option label="待生效" value="APPROVED_PENDING" />
        <el-option label="已生效" value="ACTIVE" />
        <el-option label="已驳回" value="REJECTED" />
      </el-select>
      <el-input v-model="owQuery.keyword" placeholder="用户/角色/理由" clearable
        style="width: 200px" @keyup.enter="loadOutWindow" @clear="loadOutWindow" />
      <el-button type="primary" @click="loadOutWindow">查询</el-button>
      <el-tag type="warning" style="margin-left: 8px">
        窗口外提交的申请自动升级 CCB 三方会签（运维负责人 + 架构委员会代表 + 业务 Owner）
      </el-tag>
    </div>
    <el-table :data="outWindowRows" v-loading="owLoading" border stripe>
      <el-table-column prop="targetUsername" label="被授权人" width="130" />
      <el-table-column label="目标角色" min-width="180">
        <template #default="{ row }">{{ fmtRoles(row.rolesJson) }}</template>
      </el-table-column>
      <el-table-column label="风险" width="90">
        <template #default="{ row }">
          <el-tag :type="riskType(row.riskLevel)" size="small">{{ riskLabel(row.riskLevel) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="窗口外变更" width="100" align="center">
        <template #default>
          <el-tag type="warning" size="small">窗口外</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createDate" label="提交时间" width="170" :formatter="(_, r) => fmtTime(r.createDate)" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情/签署</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 申请详情抽屉（跨页共用，就地签署） -->
    <RequestDetailDrawer />
  </div>
</template>

<script setup>
/**
 * 16.4.2 窗口外升级审批（拆分自原权限申请操作台）：窗口外申请台账 + 就地签署。
 * 公共数据与详情抽屉状态见 usePermissionRequest.js。
 */
import { ref, onMounted } from 'vue'
import * as api from '@/api/system/permission-request'
import { usePermissionRequest } from './usePermissionRequest'
import { fmtRoles, fmtTime, riskType, riskLabel, statusType, statusLabel } from './request-helpers'
import RequestDetailDrawer from './_parts/RequestDetailDrawer.vue'

const { loadCommon, openDetail } = usePermissionRequest()

const outWindowRows = ref([])
const owLoading = ref(false)
const owQuery = ref({ status: '', keyword: '' })

async function loadOutWindow() {
  owLoading.value = true
  try {
    const { data } = await api.requestList({
      status: owQuery.value.status || undefined,
      keyword: owQuery.value.keyword || undefined,
      outWindow: 'true'
    })
    outWindowRows.value = data || []
  } finally {
    owLoading.value = false
  }
}

onMounted(async () => {
  await loadCommon()
  loadOutWindow()
})
</script>

<style scoped>
.pr-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
</style>
