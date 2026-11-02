<template>
  <div class="pr-page">
    <!-- ================= 16.3.3 到期回收 ================= -->
    <div class="toolbar">
      <el-select v-model="ledQuery.status" placeholder="状态" clearable style="width: 150px"
        @change="loadLedger">
        <el-option label="生效中" value="ACTIVE" />
        <el-option label="已回收" value="RECLAIMED" />
        <el-option label="已过期" value="EXPIRED" />
      </el-select>
      <el-button type="primary" @click="loadLedger">查询</el-button>
      <el-button type="warning" plain @click="doSweep">到期扫描（回收+7日提醒）</el-button>
    </div>
    <el-table :data="ledger" v-loading="ledLoading" border stripe>
      <el-table-column prop="username" label="用户" width="130" />
      <el-table-column label="角色" min-width="160">
        <template #default="{ row }">
          {{ row.roleCode }}<span class="muted" v-if="row.roleName">（{{ row.roleName }}）</span>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.source === 'EXCEPTION'" type="warning" size="small">例外</el-tag>
          <el-tag v-else type="info" size="small">常规</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="到期时间" width="170">
        <template #default="{ row }">
          <span :style="expiring(row) ? 'color:#E6A23C;font-weight:600' : ''">
            {{ fmtTime(row.expireAt) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="ledStatusType(row.status)" size="small">{{ ledStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="申请单" width="150">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetailById(row.applyId)">查看</el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <template v-if="row.status === 'ACTIVE'">
            <el-button link type="danger" @click="doReclaim(row)">回收</el-button>
            <el-button link type="primary" @click="renew(row)">续期</el-button>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建申请对话框（续期 = 重新提交完整申请单） -->
    <CreateRequestDialog />
    <!-- 申请详情抽屉（申请单查看） -->
    <RequestDetailDrawer />
  </div>
</template>

<script setup>
/**
 * 16.3.3 到期回收（拆分自原权限申请操作台）：授权台账 / 到期扫描 / 回收 / 续期。
 * 台账为本页独有；公共数据、续期弹窗与详情抽屉见 usePermissionRequest.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/permission-request'
import { usePermissionRequest } from './usePermissionRequest'
import { fmtTime, ledStatusType, ledStatusLabel } from './request-helpers'
import CreateRequestDialog from './_parts/CreateRequestDialog.vue'
import RequestDetailDrawer from './_parts/RequestDetailDrawer.vue'

const {
  loadCommon, form, blankForm, createDlg,
  openDetailById
} = usePermissionRequest()

const ledger = ref([])
const ledLoading = ref(false)
const ledQuery = ref({ status: 'ACTIVE' })

async function loadLedger() {
  ledLoading.value = true
  try {
    const { data } = await api.ledgerList({ status: ledQuery.value.status || undefined })
    ledger.value = data || []
  } finally {
    ledLoading.value = false
  }
}

function expiring(row) {
  if (row.status !== 'ACTIVE' || !row.expireAt) return false
  const diff = new Date(row.expireAt).getTime() - Date.now()
  return diff > 0 && diff <= 7 * 24 * 3600 * 1000
}

async function doReclaim(row) {
  await ElMessageBox.confirm(
    `回收 ${row.username} 的角色 ${row.roleCode}？回收前自动留权限快照`, '确认回收',
    { type: 'warning' })
  const { data } = await api.reclaimLedger(row.id)
  ElMessage.success(data.reclaimed ? '已回收' : '该台账已是 ' + data.status)
  loadLedger()
}

async function doSweep() {
  const { data } = await api.sweepLedger()
  ElMessage.success(`扫描完成：回收 ${data.reclaimed}，提醒 ${data.reminded}，失败 ${data.failed}`)
  loadLedger()
}

/** 续期 = 重新提交完整申请单（带入原角色），原授予到期照常回收 */
function renew(row) {
  form.value = blankForm()
  form.value.targetUserId = row.targetUserId
  form.value.roleCodes = [row.roleCode]
  form.value.reason = '到期续期申请（原申请单 ' + row.applyId + '）'
  createDlg.value = true
}

onMounted(async () => {
  await loadCommon()
  loadLedger()
})
</script>

<style scoped>
.pr-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
</style>
