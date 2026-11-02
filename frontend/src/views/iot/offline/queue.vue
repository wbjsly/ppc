<template>
  <!-- 13.5.1 离线作业：PdaShell 仿真壳 + 本地队列视图 -->
  <div>
    <PdaShell title="离线作业" :scan="false" @sync="doUpload">
      <el-form label-width="90px" size="small">
        <el-form-item label="设备号">
          <el-input v-model="deviceCode" placeholder="PDA 设备编码" />
        </el-form-item>
        <el-form-item>
          <el-button size="small" @click="enqueueDemo">入队演示记录</el-button>
          <el-button size="small" type="primary" @click="refreshLock">锁定状态</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="lockInfo" :type="lockInfo.warn ? 'warning' : 'success'" :closable="false"
                style="margin-bottom:8px"
                :title="lockInfo.locked ? '终端已锁定（仅可同步/查看历史）'
                  : (lockInfo.warn ? `预警：剩余 ${lockInfo.pendingHours ?? '?'}h 将锁定` : '离线缓存正常')"
                :description="lockInfo.willLockAt ? `锁定时点：${lockInfo.willLockAt}` : ''" />
      <el-table :data="localRows" size="small">
        <el-table-column prop="idempotentKey" label="幂等键" show-overflow-tooltip />
        <el-table-column prop="bizType" label="类型" width="80" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="stType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="clientTs" label="客户端时间" width="160" />
      </el-table>
      <div style="font-size:12px;color:#909399;margin-top:8px">
        断网时业务照常入队（AES-GCM 加密），恢复后经同步栏批量补传。
      </div>
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.5.1 离线作业（拆分自原离线同步操作台）：本地队列视图 + 入队演示 + 锁定状态。
 * 设备号/本地队列/批量补传见 useOfflineSync.js（与同步补传页共享）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import { syncLockStatusApi } from '@/api/iot/sync'
import * as queue from '@/offline/queue'
import { useOfflineSync } from './useOfflineSync'

const { deviceCode, localRows, stType, refreshLocal, doUpload } = useOfflineSync()

const lockInfo = ref(null)

async function enqueueDemo() {
  const ok = await queue.enqueue({
    idempotentKey: `DEMO-${Date.now()}`,
    deviceCode: deviceCode.value,
    bizType: 'SCAN',
    payload: { n: Math.floor(Math.random() * 100) },
    sessionSeed: 'iot-session',
  })
  ElMessage[ok ? 'success' : 'warning'](ok ? '已入队（AES-GCM 加密）' : '入队失败/幂等键重复')
  refreshLocal()
}

async function refreshLock() {
  const r = await syncLockStatusApi(deviceCode.value)
  lockInfo.value = r.data
}

onMounted(() => {
  refreshLocal()
})
</script>
