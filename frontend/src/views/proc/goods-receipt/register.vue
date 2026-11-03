<template>
  <div class="page">
    <div class="card-header">
      <span style="font-weight: bold; font-size: 16px;">{{ title }}</span>
      <span class="tip">2.4 收货管理 · FR-4.2-4-1 · C-4.2-04 容差 0.5% · BR-4.2-21/22/28/49</span>
    </div>

    <PageInfo title="按 PO 登记（默认）：带出未清量逐行录实收，提交时按 0.5% 容差分流（BR-4.2-20/21/22）" description="无 PO 收货（FREE）为辅入口：免容差、不回写 PO，仍走待检与过账。核对送货单与 PO：预填值即 PO 未清量，实收与之对照着色。" style="margin-bottom: 10px;" />

    <div class="toolbar">
      <el-radio-group v-model="sourceType" size="small">
        <el-radio-button label="PO">按 PO 登记（默认）</el-radio-button>
        <el-radio-button label="FREE">无 PO 收货（FREE）</el-radio-button>
      </el-radio-group>
      <template v-if="sourceType === 'PO'">
        <el-select v-model="poId" filterable placeholder="选择已下达（APPROVED）PO"
                   style="width: 340px;" @change="loadPoLines">
          <el-option v-for="p in poCandidates" :key="p.id"
                     :label="`${p.poNo}（${p.supplierName}）`" :value="p.id" />
        </el-select>
      </template>
      <template v-else>
        <el-select v-model="freeForm.supplierId" filterable placeholder="选择供应商" style="width: 240px;">
          <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
        </el-select>
      </template>
    </div>

    <!-- PO 未清行 / FREE 手输行 -->
    <el-table :data="sourceType === 'PO' ? poRows : freeRows" size="small" border>
      <el-table-column label="物料" min-width="180">
        <template #default="{ row }">
          <template v-if="sourceType === 'PO'">{{ row.itemCode }} {{ row.itemName }}</template>
          <template v-else>
            <el-select v-model="row.itemCode" filterable placeholder="选择物料" style="width: 100%;">
              <el-option v-for="o in itemOptions" :key="o.code" :label="`[${o.code}] ${o.name}`" :value="o.code" />
            </el-select>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="下单量" width="90" align="right" v-if="sourceType === 'PO'">
        <template #default="{ row }">{{ row.qty }}</template>
      </el-table-column>
      <el-table-column label="已收" width="80" align="right" v-if="sourceType === 'PO'">
        <template #default="{ row }">{{ row.receivedQty }}</template>
      </el-table-column>
      <el-table-column label="未清量（预填）" width="120" align="right" v-if="sourceType === 'PO'">
        <template #default="{ row }"><b>{{ row.openQty }}</b></template>
      </el-table-column>
      <el-table-column label="实收数量" width="150">
        <template #default="{ row }">
          <el-input-number v-model="row.receivedQty" :min="0" :precision="4" size="small" style="width: 130px;" />
        </template>
      </el-table-column>
      <el-table-column label="容差预演" width="170">
        <template #default="{ row }">
          <template v-if="sourceType === 'FREE'">
            <el-tag size="small" type="info">FREE 免容差</el-tag>
          </template>
          <template v-else>
            <el-tag size="small" :type="preview(row).type">{{ preview(row).text }}</el-tag>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="" width="70" v-if="sourceType === 'FREE'">
        <template #default="{ $index }">
          <el-button link type="danger" @click="freeRows.splice($index, 1)">删行</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button v-if="sourceType === 'FREE' && canCreate" size="small" style="margin-top: 6px;" @click="freeRows.push({ itemCode: '', receivedQty: null })">+ 加行</el-button>

    <!-- 头信息 -->
    <el-divider content-position="left">到货信息（FR-4.2-4-1 自动记录项）</el-divider>
    <el-form :inline="true" size="small" label-width="80px">
      <el-form-item label="到货日期" required>
        <el-date-picker v-model="head.arrivalDate" type="date" value-format="YYYY-MM-DD" style="width: 150px;" />
      </el-form-item>
      <el-form-item label="送货单号">
        <el-input v-model="head.deliveryNote" placeholder="送货单号" style="width: 150px;" />
      </el-form-item>
      <el-form-item label="批次号">
        <el-input v-model="head.batchNo" placeholder="到货批次号" style="width: 150px;" />
      </el-form-item>
      <el-form-item label="运输信息">
        <el-input v-model="head.transportInfo" placeholder="车牌号/快递单号" style="width: 170px;" />
      </el-form-item>
      <el-form-item label="外包装">
        <el-input v-model="head.packageCondition" placeholder="外包装状况" style="width: 140px;" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="head.remark" placeholder="可选" style="width: 180px;" />
      </el-form-item>
      <el-form-item>
        <el-button v-if="canCreate" type="primary" :loading="saving" @click="submitCreate">提交登记</el-button>
        <el-button @click="resetForm">重置</el-button>
      </el-form-item>
    </el-form>

    <el-divider content-position="left">收货单列表</el-divider>
    <div class="toolbar">
      <el-select v-model="grQuery.status" placeholder="状态" clearable style="width: 130px;" @change="loadGr(1)">
        <el-option label="待过账" value="CREATED" />
        <el-option label="已过账" value="POSTED" />
        <el-option label="已作废" value="CANCELLED" />
      </el-select>
      <el-input v-model="grQuery.keyword" placeholder="收货单号/PO号/供应商" clearable style="width: 200px;" @keyup.enter="loadGr(1)" />
      <el-button @click="loadGr(1)">查询</el-button>
    </div>
    <el-table :data="grList" size="small" border v-loading="loading">
      <el-table-column prop="grNo" label="收货单号" width="160" />
      <el-table-column label="来源" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.sourceType === 'FREE' ? 'warning' : 'primary'" size="small">{{ row.sourceType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="poNo" label="PO" width="150" />
      <el-table-column prop="supplierName" label="供应商" min-width="130" />
      <el-table-column prop="arrivalDate" label="到货日期" width="105" />
      <el-table-column prop="batchNo" label="批次" width="110" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="postingDocNo" label="入库凭证" width="140" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="detailRef?.open(row.id)">详情</el-button>
          <el-button v-if="row.status === 'CREATED' && canCreate" link type="danger" @click="doCancel(row)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>
        <AppPagination
      style="margin-top: 8px;"
      :total="grTotal"
      v-model:page-size="grQuery.size"
      :current-page="grQuery.current"
      @current-change="loadGr"
    />

    <GrDetailDrawer ref="detailRef" />
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 2.4.1 到货登记（拆分自原收货管理操作台）：按 PO / FREE 登记、0.5% 容差预演、
 * 收货单列表与作废、详情抽屉。共享状态与角色见 useGoodsReceipt.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPoCandidatesApi, getPoLinesApi, createGrByPoApi, createGrFreeApi,
  getGrPageApi, cancelGrApi
} from '@/api/proc/goods-receipt'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { getItemOptionsApi } from '@/api/mdm/item'
import { useGoodsReceipt } from './useGoodsReceipt'
import { statusName, statusTag } from './gr-helpers'
import GrDetailDrawer from './_parts/GrDetailDrawer.vue'

const route = useRoute()
const title = computed(() => route.meta.title || '到货登记')
const { loading, canCreate } = useGoodsReceipt()
const detailRef = ref(null)
const saving = ref(false)

// ---------- 登记表单 ----------
const sourceType = ref('PO')
const poId = ref('')
const poCandidates = ref([])
const poRows = ref([])
const freeRows = ref([{ itemCode: '', receivedQty: null }])
const suppliers = ref([])
const itemOptions = ref([])
const freeForm = ref({ supplierId: '' })
const head = reactive({ arrivalDate: '', deliveryNote: '', batchNo: '', transportInfo: '', packageCondition: '', remark: '' })

const tol = 0.005
function preview(row) {
  const open = Number(row.openQty || 0)
  const r = Number(row.receivedQty || 0)
  if (!r || !open) return { type: 'info', text: '—' }
  if (r > open * (1 + tol)) {
    const frozen = r - open * (1 + tol)
    return { type: 'danger', text: `超交，冻结 ${frozen.toFixed(4)}` }
  }
  if (r < open * (1 - tol)) {
    return { type: 'warning', text: `短交 ${(open - r).toFixed(4)}` }
  }
  return { type: 'success', text: '容差内自动核销' }
}

async function loadOptions() {
  const [{ data: pos }, { data: sups }, { data: items }] = await Promise.all([
    getPoCandidatesApi(), getSupplierPageApi({ current: 1, size: 200 }), getItemOptionsApi()
  ])
  poCandidates.value = pos.records || []
  suppliers.value = sups.records || sups || []
  itemOptions.value = items || []
}

async function loadPoLines() {
  if (!poId.value) return
  const { data } = await getPoLinesApi(poId.value)
  poRows.value = (data.lines || []).map(l => ({ ...l, receivedQty: Number(l.openQty) }))
}

function resetForm() {
  poId.value = ''
  poRows.value = []
  freeRows.value = [{ itemCode: '', receivedQty: null }]
  freeForm.value = { supplierId: '' }
  Object.assign(head, { arrivalDate: '', deliveryNote: '', batchNo: '', transportInfo: '', packageCondition: '', remark: '' })
}

async function submitCreate() {
  if (!head.arrivalDate) { ElMessage.warning('到货日期必填'); return }
  let payload
  if (sourceType.value === 'PO') {
    if (!poId.value) { ElMessage.warning('请选择 PO'); return }
    const lines = poRows.value
      .filter(r => r.receivedQty != null && Number(r.receivedQty) > 0)
      .map(r => ({ poLineId: r.id, receivedQty: Number(r.receivedQty) }))
    if (!lines.length) { ElMessage.warning('至少一行实收 > 0'); return }
    payload = { poId: poId.value, ...head, lines }
  } else {
    if (!freeForm.value.supplierId) { ElMessage.warning('请选择供应商'); return }
    const lines = freeRows.value
      .filter(r => r.itemCode && r.receivedQty != null && Number(r.receivedQty) > 0)
      .map(r => ({ itemCode: r.itemCode, receivedQty: Number(r.receivedQty) }))
    if (!lines.length) { ElMessage.warning('至少一行有效物料与实收'); return }
    payload = { supplierId: freeForm.value.supplierId, ...head, lines }
  }
  saving.value = true
  try {
    const res = sourceType.value === 'PO' ? await createGrByPoApi(payload) : await createGrFreeApi(payload)
    const d = res.data || {}
    const diffs = (d.differences || []).length
    ElMessage.success(`登记成功：${d.gr?.grNo}（差异单 ${diffs} 张）`)
    resetForm()
    loadGr(1)
    detailRef.value?.open(d.gr?.id)
  } catch (e) {
    ElMessage.error(e?.message || '登记失败')
  } finally {
    saving.value = false
  }
}

// ---------- 收货单列表 ----------
const grList = ref([])
const grTotal = ref(0)
const grQuery = reactive({ status: '', keyword: '', current: 1, size: 20 })
async function loadGr(page) {
  grQuery.current = page || 1
  loading.value = true
  try {
    const { data } = await getGrPageApi({ current: grQuery.current, size: grQuery.size, status: grQuery.status, keyword: grQuery.keyword })
    grList.value = data.records || []
    grTotal.value = Number(data.total) || 0
  } finally { loading.value = false }
}

function doCancel(row) {
  ElMessageBox.prompt('作废原因（≥2 字）', '作废收货单', { inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字' })
    .then(async ({ value }) => {
      await cancelGrApi(row.id, value)
      ElMessage.success('已作废')
      loadGr(grQuery.current)
    }).catch(() => {})
}

onMounted(async () => {
  await loadOptions()
  loadGr(1)
})
</script>

<style scoped>
.page { padding: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.tip { color: #909399; font-size: 12px; }
</style>
