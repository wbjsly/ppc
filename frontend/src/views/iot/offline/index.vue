<template>
  <!-- 13.5.1 离线作业：PdaShell 仿真壳 + 本地队列视图 -->
  <div v-if="tab === 'queue'">
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

  <!-- 13.5.2 冲突解决 / 13.5.3 同步补传：宽页 -->
  <div v-else style="padding:16px">
    <template v-if="tab === 'conflict'">
      <h3>冲突解决（BL-4.11-01 字典序：VER_NO &gt; CLIENT_TS &gt; IDEMPOTENT_KEY）</h3>
      <el-table :data="conflicts" size="small" v-loading="loading">
        <el-table-column prop="conflictType" label="类型" width="90" />
        <el-table-column prop="fieldPath" label="业务键" show-overflow-tooltip />
        <el-table-column prop="dictSeqKey" label="裁决键" show-overflow-tooltip />
        <el-table-column label="级别" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.needFinApproval === '1'" size="small" type="danger">L2 财务</el-tag>
            <el-tag v-else size="small" type="info">常规</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.status !== 'RESOLVED'">
              <el-button size="small" type="success" link @click="resolve(row, 'APPROVE')">采纳</el-button>
              <el-button size="small" type="danger" link @click="resolve(row, 'REJECT')">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-alert v-if="hint" type="info" :closable="false" style="margin-top:8px" :title="hint" />
    </template>

    <template v-else>
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
    </template>
  </div>
</template>

<script setup>
/**
 * 13.5 离线同步三叶（add-iot-mobile）：
 * 13.5.1 离线作业（PdaShell 队列视图+锁定状态）/ 13.5.2 冲突解决（字典序裁决交互）/
 * 13.5.3 同步补传（IndexedDB 分批上传+清理+锁定扫描）。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import {
  syncUploadApi, syncConflictsApi, syncResolveApi, syncLockStatusApi,
  syncQueueApi, syncCleanupApi, syncScanLocksApi,
} from '@/api/iot/sync'
import * as queue from '@/offline/queue'
import { conflictHint } from '@/offline/conflict'

const route = useRoute()
const tab = computed(() => route.meta.tab || 'queue')

const deviceCode = ref('PDA-WEB')
const localRows = ref([])
const serverQueue = ref([])
const conflicts = ref([])
const lockInfo = ref(null)
const hint = ref('')
const loading = ref(false)
const uploading = ref(false)
const uploadResult = ref(null)

function stType(s) {
  return { PENDING: 'warning', SYNCED: 'success', FAILED: 'danger',
    CONFLICT: 'danger', DUPLICATE: 'info' }[s] || 'info'
}

async function refreshLocal() {
  localRows.value = await loadLocalRows()
}

async function loadLocalRows() {
  if (typeof indexedDB === 'undefined') {
    return []
  }
  try {
    const db = await new Promise((resolve, reject) => {
      const req = indexedDB.open('iot-offline', 1)
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
    return await new Promise((resolve, reject) => {
      const t = db.transaction('queue', 'readonly')
      const r = t.objectStore('queue').getAll()
      r.onsuccess = () => resolve(r.result || [])
      r.onerror = () => reject(r.error)
    })
  } catch (e) {
    return []
  }
}

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

async function doUpload() {
  uploading.value = true
  try {
    const res = await queue.uploadQueue(batch => syncUploadApi(batch))
    uploadResult.value = res
    refreshLocal()
    await loadServerQueue()
    ElMessage.success(`补传完成：${res.uploaded} 条`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '补传失败')
  } finally {
    uploading.value = false
  }
}

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

async function loadServerQueue() {
  try {
    const r = await syncQueueApi({ deviceCode: deviceCode.value })
    serverQueue.value = r.data || []
  } catch (e) { serverQueue.value = [] }
}

async function loadConflicts() {
  loading.value = true
  try {
    const r = await syncConflictsApi()
    conflicts.value = r.data || []
  } finally {
    loading.value = false
  }
}

async function resolve(row, decision) {
  try {
    const r = await syncResolveApi(row.id, { decision, remark: decision === 'APPROVE'
      ? '移动端采纳服务端胜者' : '驳回保留待人工' })
    hint.value = conflictHint({ conflictType: row.conflictType,
      needFinApproval: row.needFinApproval })
    ElMessage.success(`裁决完成：${r.data?.status}`)
    loadConflicts()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '裁决失败')
  }
}

onMounted(() => {
  if (tab.value === 'queue') refreshLocal()
  if (tab.value === 'conflict') loadConflicts()
  if (tab.value === 'replay') loadServerQueue()
})
</script>
