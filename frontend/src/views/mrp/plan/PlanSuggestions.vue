<template>
  <div class="page">
    <el-page-header :content="title" style="margin-bottom: 8px" />

    <!-- 运行触发条 -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">MRP 运行</span>
        <el-select v-model="scopeType" style="width: 150px;" size="small">
          <el-option label="全量" value="FULL" />
          <el-option label="按物料分类" value="CATEGORY" />
          <el-option label="按物料组" value="GROUP" />
        </el-select>
        <el-input v-if="scopeType !== 'FULL'" v-model="scopeValue" size="small" clearable
                  :placeholder="scopeType === 'CATEGORY' ? '分类编码（如 0001）' : '物料组编码（如 STRUCT）'"
                  style="width: 200px;" />
        <el-button type="primary" size="small" :loading="running" @click="doRun">运行 MRP</el-button>
        <el-button size="small" @click="loadRuns">运行历史</el-button>
        <span class="tip">手动触发 + 同刻单运行互斥；重跑自动取代同物料未终态旧建议</span>
      </div>
    </div>

    <!-- 筛选 -->
    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 130px;" @change="loadData">
          <el-option label="待审核" value="PENDING" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="已转换" value="CONVERTED" />
          <el-option label="已取消" value="CANCELLED" />
          <el-option label="已被取代" value="SUPERSEDED" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" clearable placeholder="物料编码/名称" style="width: 200px;"
                  @keyup.enter="loadData" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">查询</el-button>
        <el-button v-if="selectedConfirmed.length" type="success" @click="doConvert">
          {{ type === 'PURCHASE' ? '生成请购单' : '转计划工单' }}（已选 {{ selectedConfirmed.length }}）
        </el-button>
      </el-form-item>
      <span class="tip">勾选「已确认」行批量转正；留痕列可展开看净算算式</span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe @selection-change="onSelect">
      <el-table-column type="selection" width="42" :selectable="r => r.status === 'CONFIRMED'" />
      <el-table-column prop="itemCode" label="物料编码" width="150" />
      <el-table-column prop="itemName" label="物料名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="suggestQty" label="建议量" width="100" />
      <el-table-column prop="confirmQty" label="确认量" width="90">
        <template #default="{ row }">{{ row.confirmQty ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="reqDate" label="需求日期" width="105" />
      <el-table-column prop="orderDate" label="建议下单日" width="105">
        <template #default="{ row }">
          <span :class="{ overdue: row.overdueFlag === '1' }">{{ row.orderDate || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标记" width="86">
        <template #default="{ row }">
          <el-tag v-if="row.overdueFlag === '1'" type="danger" size="small">逾期</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注（MOQ/超库存/替代溯源）" min-width="200" show-overflow-tooltip />
      <el-table-column prop="targetNo" label="转正单号" width="150">
        <template #default="{ row }">{{ row.targetNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING' && row.type !== 'EXCESS'">
            <el-button link type="primary" size="small" @click="openConfirm(row)">确认</el-button>
            <el-button link type="danger" size="small" @click="openCancel(row)">取消</el-button>
          </template>
          <el-button link type="info" size="small" @click="openTrace(row)">算式</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无建议（先运行 MRP）" />

    <!-- 运行历史抽屉 -->
    <el-drawer v-model="runsVisible" title="MRP 运行历史" size="640px">
      <el-table :data="runs" size="small" border>
        <el-table-column prop="runNo" label="运行号" width="160" />
        <el-table-column label="范围" width="140">
          <template #default="{ row }">{{ scopeText(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.runStatus === 'DONE' ? 'success'
              : row.runStatus === 'FAILED' ? 'danger' : 'warning'" size="small">
              {{ row.runStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="扫描/建议/异常" width="130">
          <template #default="{ row }">
            {{ row.statScanned }}/{{ row.statSuggested }}/{{ row.statException }}
          </template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.runAt) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 确认弹窗 -->
    <el-dialog v-model="confirmVisible" title="确认建议" width="480px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 8px;"
                :title="`原建议量 ${current?.suggestQty ?? '-'}（确认可改，原值留痕）`" />
      <el-form label-width="90px" size="small">
        <el-form-item label="确认量">
          <el-input-number v-model="confirmQty" :min="0.0001" :precision="4"
                           controls-position="right" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="需求日期">
          <el-date-picker v-model="confirmDate" type="date" value-format="YYYY-MM-DD"
                          style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doConfirm">确认</el-button>
      </template>
    </el-dialog>

    <!-- 取消弹窗 -->
    <el-dialog v-model="cancelVisible" title="取消建议" width="480px">
      <el-input v-model="cancelReason" type="textarea" :rows="3" maxlength="500" show-word-limit
                placeholder="取消原因必填（FR-4.5-2-7，留痕）" />
      <template #footer>
        <el-button @click="cancelVisible = false">关闭</el-button>
        <el-button type="danger" :loading="saving" @click="doCancel">确认取消</el-button>
      </template>
    </el-dialog>

    <!-- 净算算式（留痕） -->
    <el-dialog v-model="traceVisible" title="净算留痕（单期快照）" width="520px">
      <el-descriptions v-if="current" :column="1" border size="small">
        <el-descriptions-item label="需求量">{{ current.demandQty }}</el-descriptions-item>
        <el-descriptions-item label="库存 OnHand">{{ current.onHandQty }}</el-descriptions-item>
        <el-descriptions-item label="在制 InProcess（桩0）">{{ current.inProcessQty }}</el-descriptions-item>
        <el-descriptions-item label="在途 Incoming">{{ current.inTransitQty }}</el-descriptions-item>
        <el-descriptions-item label="NetReq = 需求−库存−在制−在途">
          <b>{{ current.netReq }}</b>
        </el-descriptions-item>
        <el-descriptions-item label="需求日期">{{ current.reqDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="建议下单日">{{ current.orderDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="所属运行">{{ current.runId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ current.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="traceVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/store/user'
import {
  runMrpApi, getRunsApi, getSuggestionsApi,
  confirmSuggestionApi, cancelSuggestionApi, convertPrApi, convertMoApi
} from '@/api/mrp/plan'

// 5.3.1/5.3.2 共享建议面板：type=PURCHASE|PRODUCTION
const props = defineProps({
  type: { type: String, required: true },
  title: { type: String, required: true }
})

const route = useRoute()
const userStore = useUserStore()
const canRun = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_PLANNER')
})

const loading = ref(false)
const running = ref(false)
const saving = ref(false)
const rows = ref([])
const selected = ref([])
const query = ref({ status: '', keyword: route.query.item || '' })
const scopeType = ref('FULL')
const scopeValue = ref('')

const runsVisible = ref(false)
const runs = ref([])

const confirmVisible = ref(false)
const cancelVisible = ref(false)
const traceVisible = ref(false)
const current = ref(null)
const confirmQty = ref(0)
const confirmDate = ref('')
const cancelReason = ref('')

const selectedConfirmed = computed(() =>
  selected.value.filter(r => r.status === 'CONFIRMED'))

function statusText(s) {
  return { PENDING: '待审核', CONFIRMED: '已确认', CONVERTED: '已转换',
    CANCELLED: '已取消', SUPERSEDED: '已被取代' }[s] || s
}
function statusTag(s) {
  return { PENDING: 'info', CONFIRMED: 'warning', CONVERTED: 'success',
    CANCELLED: 'danger', SUPERSEDED: 'info' }[s] || 'info'
}
function scopeText(r) {
  if (r.scopeType === 'FULL') return '全量'
  if (r.scopeType === 'CATEGORY') return `分类 ${r.scopeValue || ''}`
  return `物料组 ${r.scopeValue || ''}`
}
function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadData() {
  loading.value = true
  try {
    const params = { type: props.type }
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    const res = await getSuggestionsApi(params)
    rows.value = res.data || []
  } catch (e) {
    console.warn('[plan] load failed', e)
  } finally {
    loading.value = false
  }
}

async function loadRuns() {
  const res = await getRunsApi()
  runs.value = res.data || []
  runsVisible.value = true
}

async function doRun() {
  if (scopeType.value !== 'FULL' && !scopeValue.value) {
    ElMessage.warning('按分类/物料组运行须指定范围值')
    return
  }
  running.value = true
  try {
    const res = await runMrpApi({
      scopeType: scopeType.value,
      scopeValue: scopeType.value === 'FULL' ? '' : scopeValue.value
    })
    ElMessage.success(`运行完成 ${res.data.runNo}：建议 ${res.data.suggested} 条、异常 ${res.data.exceptions} 条`)
    loadData()
  } catch (e) {
    console.warn('[plan] run failed', e)
  } finally {
    running.value = false
  }
}

function onSelect(sel) {
  selected.value = sel
}

function openConfirm(row) {
  current.value = row
  confirmQty.value = Number(row.suggestQty)
  confirmDate.value = row.reqDate || ''
  confirmVisible.value = true
}

async function doConfirm() {
  saving.value = true
  try {
    await confirmSuggestionApi(current.value.id, {
      confirmQty: confirmQty.value,
      confirmDate: confirmDate.value || ''
    })
    ElMessage.success('已确认（原值留痕）')
    confirmVisible.value = false
    loadData()
  } catch (e) {
    console.warn('[plan] confirm failed', e)
  } finally {
    saving.value = false
  }
}

function openCancel(row) {
  current.value = row
  cancelReason.value = ''
  cancelVisible.value = true
}

async function doCancel() {
  if (!cancelReason.value || !cancelReason.value.trim()) {
    ElMessage.warning('取消原因必填')
    return
  }
  saving.value = true
  try {
    await cancelSuggestionApi(current.value.id, cancelReason.value.trim())
    ElMessage.success('已取消（原因留痕）')
    cancelVisible.value = false
    loadData()
  } catch (e) {
    console.warn('[plan] cancel failed', e)
  } finally {
    saving.value = false
  }
}

async function doConvert() {
  const n = selectedConfirmed.value.length
  await ElMessageBox.confirm(
    props.type === 'PURCHASE'
      ? `批量生成请购单（${n} 行，复用自动请购链路，物料停用将整批阻断）？`
      : `批量转计划工单（${n} 行，PMO 占位单号，5.4 工单管理落地后对接）？`,
    props.type === 'PURCHASE' ? '生成请购单' : '转计划工单',
    { type: 'warning' }
  )
  saving.value = true
  try {
    const ids = selectedConfirmed.value.map(r => r.id)
    const res = props.type === 'PURCHASE'
      ? await convertPrApi(ids) : await convertMoApi(ids)
    ElMessage.success(`已转换 ${res.data.converted} 行 → ${res.data.targetNo}`)
    loadData()
  } catch (e) {
    console.warn('[plan] convert failed', e)
  } finally {
    saving.value = false
  }
}

function openTrace(row) {
  current.value = row
  traceVisible.value = true
}

onMounted(loadData)
</script>

<style scoped>
.filter-bar {
  margin-bottom: 4px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
.section {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.section-title {
  font-weight: 600;
  color: #303133;
  font-size: 13px;
}
.overdue {
  color: #f56c6c;
  font-weight: 600;
}
</style>
