import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { syncUploadApi, syncQueueApi } from '@/api/iot/sync'
import * as queue from '@/offline/queue'

/**
 * 13.5 离线同步三页共享状态（拆分自原离线同步操作台，design D2 composable 规则）。
 * 模块级单例：设备号、本地/服务端队列与批量补传在离线作业/同步补传两页间共享
 * （冲突解决页仅用状态标签 stType），等价原「同一组件被三条路由复用 + keep-alive」的行为。
 */
const deviceCode = ref('PDA-WEB')
const localRows = ref([])
const serverQueue = ref([])
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

async function loadServerQueue() {
  try {
    const r = await syncQueueApi({ deviceCode: deviceCode.value })
    serverQueue.value = r.data || []
  } catch (e) { serverQueue.value = [] }
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

export function useOfflineSync() {
  return {
    deviceCode, localRows, serverQueue, uploading, uploadResult,
    stType, loadLocalRows, refreshLocal, loadServerQueue, doUpload,
  }
}
