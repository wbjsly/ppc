<template>
  <!-- 13.5.3 同步补传：宽页（IndexedDB 分批上传 + 清理 + 锁定扫描） -->
  <div style="padding:16px">
    <h3>同步补传（100 条/批 · 逐条反馈）</h3>
    <el-form inline size="small">
      <el-form-item label="设备号">
        <el-input v-model="deviceCode" style="width:220px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="uploading" @click="doUpload">批量补传</el-button>
        <el-button @click="doCleanup">清理已同步</el-button>
        <el-button @click="doScanLocks">72h 锁定扫描</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="uploadResult" type="success" :closable="false" style="margin-bottom:8px"
              :title="`补传完成：同步 ${uploadResult.uploaded} 条 / ${uploadResult.batches || 0} 批`" />
    <el-table :data="serverQueue" size="small">
      <el-table-column prop="idempotentKey" label="幂等键" show-overflow-tooltip />
      <el-table-column prop="bizType" label="类型" width="90" />
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="stType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="errMsg" label="失败原因" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 13.5.3 同步补传（拆分自原离线同步操作台）：分批上传 + 清理已同步 + 72h 锁定扫描。
 * 设备号/服务端队列/批量补传见 useOfflineSync.js（与离线作业页共享）。
 */
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { syncCleanupApi, syncScanLocksApi } from '@/api/iot/sync'
import * as queue from '@/offline/queue'
import { useOfflineSync } from './useOfflineSync'

const {
  deviceCode, serverQueue, uploading, uploadResult, stType,
  refreshLocal, loadServerQueue, doUpload,
} = useOfflineSync()

async function doCleanup() {
  const r = await syncCleanupApi(deviceCode.value)
  const cleared = await queue.clearSynced()
  ElMessage.info(`服务端清理 ${r.data?.cleared || 0} 条 / 本地清除 ${cleared} 条`)
  refreshLocal()
  loadServerQueue()
}

async function doScanLocks() {
  const r = await syncScanLocksApi()
  const d = r.data || {}
  ElMessage.info(`锁定 ${(d.locked || []).length} 台，预警 ${(d.warned || []).length} 台`)
}

onMounted(() => {
  loadServerQueue()
})
</script>
