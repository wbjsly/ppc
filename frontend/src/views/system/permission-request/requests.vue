<template>
  <div class="pr-page">
    <!-- ================= 16.3.1 权限申请 ================= -->
    <div class="toolbar">
      <el-select v-model="reqQuery.status" placeholder="状态" clearable style="width: 150px"
        @change="loadRequests">
        <el-option label="审批中" value="PENDING_APPROVAL" />
        <el-option label="待生效" value="APPROVED_PENDING" />
        <el-option label="已生效" value="ACTIVE" />
        <el-option label="已驳回" value="REJECTED" />
        <el-option label="已到期" value="EXPIRED" />
      </el-select>
      <el-input v-model="reqQuery.keyword" placeholder="用户/角色/理由" clearable
        style="width: 200px" @keyup.enter="loadRequests" @clear="loadRequests" />
      <el-button type="primary" @click="loadRequests">查询</el-button>
      <el-button type="success" @click="openCreate">新建申请</el-button>
      <el-button @click="windowDlg = true">变更窗口</el-button>
    </div>
    <el-table :data="requests" v-loading="reqLoading" border stripe>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.kind === 'EXCEPTION'" type="warning" size="small">例外</el-tag>
          <el-tag v-else type="info" size="small">常规</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetUsername" label="被授权人" width="130" />
      <el-table-column label="目标角色" min-width="200">
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
      <el-table-column prop="validMonths" label="有效期(月)" width="90" />
      <el-table-column label="生效策略" width="100">
        <template #default="{ row }">{{ row.activationMode === 'SCHEDULED' ? '指定时点' : '立即' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建申请对话框（跨页共用：16.3.3 续期亦使用） -->
    <CreateRequestDialog />
    <!-- 申请详情抽屉（跨页共用） -->
    <RequestDetailDrawer />

    <!-- 变更窗口维护 -->
    <el-dialog v-model="windowDlg" title="变更窗口维护（特权权限仅窗口内生效）" width="640px">
      <el-table :data="windows" size="small" border>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="星期" width="90">
          <template #default="{ row }">周{{ ['一','二','三','四','五','六','日'][row.dayOfWeek - 1] }}</template>
        </el-table-column>
        <el-table-column label="时段" width="140">
          <template #default="{ row }">{{ row.startTime }} - {{ row.endTime }}</template>
        </el-table-column>
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === '1' ? 'success' : 'info'">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeWindow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-divider content-position="left">新增窗口</el-divider>
      <el-form inline>
        <el-form-item label="名称"><el-input v-model="winForm.name" style="width: 150px" /></el-form-item>
        <el-form-item label="星期">
          <el-select v-model="winForm.dayOfWeek" style="width: 90px">
            <el-option v-for="d in 7" :key="d" :label="'周' + ['一','二','三','四','五','六','日'][d-1]" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="起">
          <el-time-select v-model="winForm.startTime" start="00:00" step="00:30" end="23:30"
            style="width: 100px" />
        </el-form-item>
        <el-form-item label="止">
          <el-time-select v-model="winForm.endTime" start="00:30" step="00:30" end="23:59"
            style="width: 100px" />
        </el-form-item>
        <el-button type="primary" @click="submitWindow">添加</el-button>
      </el-form>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 16.3.1 权限申请（拆分自原权限申请操作台）：申请台账 / 新建申请 / 变更窗口维护。
 * 公共数据、申请列表、新建弹窗与详情抽屉状态见 usePermissionRequest.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/permission-request'
import { usePermissionRequest } from './usePermissionRequest'
import { fmtRoles, riskType, riskLabel, statusType, statusLabel } from './request-helpers'
import CreateRequestDialog from './_parts/CreateRequestDialog.vue'
import RequestDetailDrawer from './_parts/RequestDetailDrawer.vue'

const {
  loadCommon, reqQuery, loadRequests, requests, reqLoading,
  openCreate, openDetail
} = usePermissionRequest()

const windowDlg = ref(false)
const windows = ref([])
const winForm = ref({ name: '', dayOfWeek: 3, startTime: '22:00', endTime: '24:00' })

// 变更窗口
async function loadWindows() {
  const { data } = await api.windowList()
  windows.value = data || []
}
async function submitWindow() {
  const w = winForm.value
  if (!w.name || !w.startTime || !w.endTime) {
    ElMessage.error('名称与时段必填')
    return
  }
  await api.saveWindow({ ...w })
  ElMessage.success('窗口已保存')
  winForm.value = { name: '', dayOfWeek: w.dayOfWeek, startTime: '22:00', endTime: '24:00' }
  loadWindows()
}
async function removeWindow(row) {
  await ElMessageBox.confirm(`删除窗口「${row.name}」？`, '确认', { type: 'warning' })
  await api.deleteWindow(row.id)
  ElMessage.success('已删除')
  loadWindows()
}

onMounted(async () => {
  await loadCommon()
  loadRequests()
  loadWindows()
})
</script>

<style scoped>
.pr-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
</style>
