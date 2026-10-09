<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="临期锁定（4.10.2）：锁定批次台账 + 变更历史 + 人工锁定（免审批）"
      description="台账展示 EXPIRY_LOCK_FLAG=1 全量（来源区分 AUTO 系统扫描 / MANUAL 人工锁定）；人工锁定即刻生效免审批，解除唯一入口=4.10.3 评估放行（收紧免审、放宽必审）。" />

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <!-- 锁定台账 -->
      <el-tab-pane label="锁定台账" name="ledger">
        <div class="toolbar">
          <el-input v-model="filters.itemCode" placeholder="物料" clearable style="width: 150px"
            @clear="loadLedger(1)" @keyup.enter="loadLedger(1)" />
          <el-input v-model="filters.warehouseCode" placeholder="仓库" clearable style="width: 130px"
            @clear="loadLedger(1)" @keyup.enter="loadLedger(1)" />
          <el-select v-model="filters.lockSource" placeholder="锁定来源" clearable style="width: 130px"
            @change="loadLedger(1)">
            <el-option label="系统扫描 AUTO" value="AUTO" />
            <el-option label="人工锁定 MANUAL" value="MANUAL" />
          </el-select>
          <el-button @click="loadLedger(1)">查询</el-button>
          <el-button type="primary" plain :loading="scanning" @click="doScan">手动触发扫描</el-button>
          <el-button type="danger" plain v-if="canLock" @click="openManualLock">人工锁定</el-button>
        </div>

        <el-table :data="rows" v-loading="loading" size="small" border>
          <el-table-column prop="batchNo" label="批次" width="150" />
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
          <el-table-column prop="expiryDate" label="有效期至" width="110" />
          <el-table-column label="剩余天数" width="95">
            <template #default="{ row }">
              <span :class="{ 'danger-text': row.remainingDays <= 30 }">{{ row.remainingDays ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="锁定线（天）" width="105">
            <template #default="{ row }">{{ row.lockThresholdDays ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="锁定来源" width="110">
            <template #default="{ row }">
              <el-tag :type="row.lockSource === 'MANUAL' ? 'danger' : 'warning'" size="small">
                {{ row.lockSource === 'MANUAL' ? '人工' : '系统' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="放行豁免" width="130">
            <template #default="{ row }">
              <el-tag v-if="row.evalExemptUntil" type="success" size="small">
                至 {{ row.evalExemptUntil }}
              </el-tag>
              <span v-else class="dim">无</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="goEval(row)">发起评估</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="total" :page-size="filters.size"
          :current-page="filters.current" @current-change="p => { filters.current = p; loadLedger() }" />
      </el-tab-pane>

      <!-- 变更历史 -->
      <el-tab-pane label="变更历史" name="history">
        <div class="toolbar">
          <el-input v-model="histFilters.batchNo" placeholder="批次" clearable style="width: 150px"
            @clear="loadHistory" @keyup.enter="loadHistory" />
          <el-input v-model="histFilters.itemCode" placeholder="物料" clearable style="width: 150px"
            @clear="loadHistory" @keyup.enter="loadHistory" />
          <el-button @click="loadHistory">查询</el-button>
        </div>
        <el-table :data="histRows" v-loading="loading" size="small" border>
          <el-table-column prop="batchNo" label="批次" width="150" />
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column label="变化" width="110">
            <template #default="{ row }">
              {{ row.fromFlag === '1' ? '锁' : '开' }} → {{ row.toFlag === '1' ? '锁' : '开' }}
            </template>
          </el-table-column>
          <el-table-column label="来源" width="120">
            <template #default="{ row }">
              <el-tag :type="row.source === 'MANUAL' ? 'danger'
                : row.source === 'EVAL_RELEASE' ? 'success' : 'info'" size="small">
                {{ { SCAN: '每日扫描', MANUAL: '人工锁定', EVAL_RELEASE: '评估放行' }[row.source] || row.source }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operator" label="操作人" width="130" />
          <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reason || '-' }}</template>
          </el-table-column>
          <el-table-column prop="changeAt" label="时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.changeAt) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 人工锁定对话框 -->
    <el-dialog v-model="lockVisible" title="人工锁定（即刻生效，免审批）" width="520px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
        title="锁定是收紧动作，即时生效；解除须经 4.10.3 质量评估放行" />
      <el-form label-width="90px">
        <el-form-item label="物料" required>
          <el-input v-model="lockForm.itemCode" placeholder="物料编码" />
        </el-form-item>
        <el-form-item label="批次" required>
          <el-input v-model="lockForm.batchNo" placeholder="批次号" />
        </el-form-item>
        <el-form-item label="锁定原因" required>
          <el-input v-model="lockForm.reason" type="textarea" :rows="3" maxlength="500"
            placeholder="主动隔离原因（必填，写入变更历史）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lockVisible = false">取消</el-button>
        <el-button type="danger" :loading="locking" @click="doLock">确认锁定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getExpiryLedgerApi, getExpiryLockHistoryApi, manualLockApi, triggerExpiryScanApi
} from '@/api/inv/expiry'
import { useUserStore } from '@/store/user'

// 4.10.2 临期锁定：台账 + 变更历史 + 手动扫描 + 人工锁定（免审批）
const router = useRouter()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const canLock = computed(() =>
  roles.value.includes('ROLE_ADMIN') || roles.value.includes('ROLE_WAREHOUSE'))

const tab = ref('ledger')
const loading = ref(false)
const scanning = ref(false)
const locking = ref(false)
const rows = ref([])
const total = ref(0)
const histRows = ref([])
const filters = reactive({ itemCode: '', warehouseCode: '', lockSource: '', current: 1, size: 20 })
const histFilters = reactive({ batchNo: '', itemCode: '' })

const lockVisible = ref(false)
const lockForm = reactive({ itemCode: '', batchNo: '', reason: '' })

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadLedger(page) {
  if (page) filters.current = page
  loading.value = true
  try {
    const res = await getExpiryLedgerApi({
      itemCode: filters.itemCode || undefined,
      warehouseCode: filters.warehouseCode || undefined,
      lockSource: filters.lockSource || undefined,
      current: filters.current, size: filters.size
    })
    rows.value = res.data?.records || []
    total.value = Number(res.data?.total || 0)
  } catch (e) {
    console.warn('[expiry-lock] ledger failed', e)
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  loading.value = true
  try {
    const res = await getExpiryLockHistoryApi({
      batchNo: histFilters.batchNo || undefined,
      itemCode: histFilters.itemCode || undefined,
      size: 200
    })
    histRows.value = res.data || []
  } catch (e) {
    console.warn('[expiry-lock] history failed', e)
  } finally {
    loading.value = false
  }
}

async function doScan() {
  scanning.value = true
  try {
    const res = await triggerExpiryScanApi()
    ElMessage.success(`扫描完成，${res.data?.changed ?? 0} 个批次标记位变化`)
    loadLedger(1)
  } catch (e) {
    console.warn('[expiry-lock] scan failed', e)
  } finally {
    scanning.value = false
  }
}

function openManualLock() {
  lockForm.itemCode = ''
  lockForm.batchNo = ''
  lockForm.reason = ''
  lockVisible.value = true
}

async function doLock() {
  if (!lockForm.itemCode.trim() || !lockForm.batchNo.trim()) {
    ElMessage.warning('物料与批次必填')
    return
  }
  if (!lockForm.reason.trim()) {
    ElMessage.warning('锁定原因必填')
    return
  }
  locking.value = true
  try {
    await manualLockApi({
      itemCode: lockForm.itemCode.trim(),
      batchNo: lockForm.batchNo.trim(),
      reason: lockForm.reason.trim()
    })
    ElMessage.success('已锁定（即时生效，解除须经 4.10.3 评估放行）')
    lockVisible.value = false
    loadLedger(1)
    if (tab.value === 'history') loadHistory()
  } catch (e) {
    console.warn('[expiry-lock] manual lock failed', e)
  } finally {
    locking.value = false
  }
}

function goEval(row) {
  router.push({ path: '/m/4.10.3', query: { itemCode: row.itemCode, batchNo: row.batchNo } })
}

function onTabChange(name) {
  // Tab 切换时刷新（人工锁定/扫描刚落的数据切回即可见）
  if (name === 'history') loadHistory()
  else loadLedger(1)
}

onMounted(() => {
  loadLedger(1)
  loadHistory()
})
</script>

<style scoped>
.page { padding: 4px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.dim { color: #909399; font-size: 12px; }
.danger-text { color: #f56c6c; font-weight: 600; }
</style>
