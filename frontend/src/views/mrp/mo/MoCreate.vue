<template>
  <div>
    <el-page-header content="工单创建（5.4.1）— 手工新建 / 从 PMO 选入；BOM·路线双快照 + 缺料预检（缺料不阻断）"
                    style="margin-bottom: 8px" />

    <el-tabs v-model="tab">
      <!-- PMO 选入 -->
      <el-tab-pane label="从计划工单（PMO）选入" name="pmo">
        <div class="toolbar">
          <el-button size="small" @click="loadPmos">刷新</el-button>
          <span class="tip">5.3 已转正且未关联工单的生产建议；同一 PMO 只能建单一次（幂等回写）</span>
        </div>
        <el-table :data="pmos" size="small" border v-loading="loading">
          <el-table-column prop="pmoNo" label="计划工单号" width="160" />
          <el-table-column prop="itemCode" label="产品编码" width="150" />
          <el-table-column prop="itemName" label="产品名称" min-width="140" show-overflow-tooltip />
          <el-table-column prop="qty" label="建议数量" width="100" />
          <el-table-column prop="reqDate" label="需求日期" width="110" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="createFromPmo(row)">建单</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 手工新建 -->
      <el-tab-pane label="手工新建" name="manual">
        <el-form :model="form" label-width="96px" style="max-width: 640px">
          <el-form-item label="产品编码" required>
            <el-select v-model="form.productCode" filterable remote reserve-keyword clearable
                       :remote-method="searchProducts" :loading="searching"
                       placeholder="搜索自制件（输入编码/名称）" style="width: 100%">
              <el-option v-for="p in productOptions" :key="p.itemCode"
                         :label="`${p.itemCode} ${p.itemName || ''}`" :value="p.itemCode" />
            </el-select>
            <div class="tip">仅自制件（MAKE）且启用；须有已发布 BOM，否则阻断创建</div>
          </el-form-item>
          <el-form-item label="计划数量" required>
            <el-input-number v-model="form.qty" :min="0.0001" :precision="4" style="width: 200px" />
            <span class="tip" v-if="soOutstanding !== null">
              SO 未交 {{ soOutstanding }}，上限 105%（BR-4.5-15）
            </span>
          </el-form-item>
          <el-form-item label="计划开工" required>
            <el-date-picker v-model="form.planStartDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="计划完工" required>
            <el-date-picker v-model="form.planEndDate" type="date" value-format="YYYY-MM-DD"
                            :disabled-date="d => d.getTime() < Date.now() - 86400000" />
            <div class="tip">完工日期不可早于今日（C-4.5-06 L1 阻断）</div>
          </el-form-item>
          <el-form-item label="优先级">
            <el-input-number v-model="form.priority" :min="1" :max="9" />
            <span class="tip">1 最高，9 最低，缺省 5</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="createManual">保存草稿（创建）</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>

    <!-- 创建结果（快照预览 + 警告） -->
    <el-dialog v-model="resultVisible" title="工单创建结果" width="780px">
      <el-alert v-if="resultMo" :title="`已创建 ${resultMo.moNo}（${resultMo.productCode}，数量 ${resultMo.qty}）`"
                type="success" :closable="false" style="margin-bottom: 8px" />
      <div v-for="(w, i) in resultWarnings" :key="i" class="warn-line">{{ w }}</div>
      <el-tabs v-if="resultMo" v-model="resultTab">
        <el-tab-pane label="BOM 快照" name="bom">
          <el-table :data="resultDetail.bomLines || []" size="small" border max-height="300">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="treeLevel" label="层" width="50" />
            <el-table-column prop="itemCode" label="子项编码" width="140" />
            <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
            <el-table-column prop="unitQty" label="用量" width="90" />
            <el-table-column prop="lossRate" label="损耗率" width="80" />
            <el-table-column label="替代料" min-width="140">
              <template #default="{ row }">{{ row.substituteInfo || '—' }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="工序快照" name="ops">
          <el-table :data="resultDetail.ops || []" size="small" border max-height="300">
            <el-table-column prop="opSeq" label="序号" width="60" />
            <el-table-column prop="opCode" label="工序" width="120" />
            <el-table-column prop="opName" label="名称" min-width="110" />
            <el-table-column prop="wcCode" label="工作中心" width="110" />
            <el-table-column prop="runHours" label="标准工时" width="90" />
            <el-table-column prop="leadTime" label="提前期" width="80" />
          </el-table>
          <el-empty v-if="!(resultDetail.ops || []).length" description="无路线快照（创建时已提示）" />
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 工单列表（本页产出可见） -->
    <el-divider content-position="left">工单列表</el-divider>
    <div class="toolbar">
      <el-select v-model="listStatus" placeholder="状态过滤" clearable style="width: 160px" size="small">
        <el-option v-for="(label, code) in STATUS" :key="code" :label="label" :value="code" />
      </el-select>
      <el-button size="small" @click="loadList">刷新</el-button>
    </div>
    <el-table :data="list" size="small" border v-loading="listLoading">
      <el-table-column prop="moNo" label="工单号" width="160" />
      <el-table-column prop="productCode" label="产品" width="130" />
      <el-table-column prop="productName" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="qty" label="数量" width="80" />
      <el-table-column prop="plannedMoNo" label="来源 PMO" width="150">
        <template #default="{ row }">{{ row.plannedMoNo || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ STATUS[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="缺料" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.shortageFlag === '1'" type="warning" size="small">缺料</el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column prop="bomVersion" label="BOM" width="70" />
      <el-table-column prop="planEndDate" label="完工日期" width="105" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="工单详情" width="760px">
      <template v-if="detailMo">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="工单号">{{ detailMo.moNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ STATUS[detailMo.status] }}</el-descriptions-item>
          <el-descriptions-item label="产品">{{ detailMo.productCode }} {{ detailMo.productName }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ detailMo.qty }}</el-descriptions-item>
          <el-descriptions-item label="计划开工">{{ detailMo.planStartDate }}</el-descriptions-item>
          <el-descriptions-item label="计划完工">{{ detailMo.planEndDate }}</el-descriptions-item>
          <el-descriptions-item label="来源 PMO">{{ detailMo.plannedMoNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="BOM 快照">{{ detailMo.bomVersion }}（{{ detailMo.sourceBomId }}）</el-descriptions-item>
          <el-descriptions-item label="创建">{{ detailMo.createBy }} {{ detailMo.createDate }}</el-descriptions-item>
          <el-descriptions-item label="审批留痕">
            提交 {{ detailMo.submitBy || '—' }} / 通过 {{ detailMo.approveBy || '—' }}
            <span v-if="detailMo.rejectReason">（驳回：{{ detailMo.rejectReason }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="释放留痕">{{ detailMo.releaseBy }} {{ detailMo.releaseAt }}</el-descriptions-item>
          <el-descriptions-item label="关闭留痕">{{ detailMo.closeBy || '—' }} {{ detailMo.closeAt || '' }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">BOM 快照行</el-divider>
        <el-table :data="detailBom" size="small" border max-height="240">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="treeLevel" label="层" width="50" />
          <el-table-column prop="itemCode" label="子项" width="140" />
          <el-table-column prop="unitQty" label="用量" width="90" />
          <el-table-column prop="lossRate" label="损耗" width="80" />
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import {
  getMosApi, getMoDetailApi, createMoApi, getCandidatePmosApi, createFromPmoApi
} from '@/api/mrp/mo'

const STATUS = {
  PLANNED: '计划', PENDING: '待审核', CONFIRMED: '已确认', RELEASED: '已释放',
  HOLD: '挂起', COMPLETED: '已完工', CLOSED: '已关闭', CANCELLED: '已取消'
}
const statusType = s => ({
  PLANNED: 'info', PENDING: 'warning', CONFIRMED: '', RELEASED: 'success',
  HOLD: 'warning', COMPLETED: 'success', CLOSED: 'info', CANCELLED: 'danger'
}[s] || 'info')

const tab = ref('pmo')
const pmos = ref([])
const loading = ref(false)
const form = ref({ productCode: '', qty: 1, planStartDate: new Date().toISOString().slice(0, 10), planEndDate: '', priority: 5 })
const productOptions = ref([])
const searching = ref(false)
const saving = ref(false)
const soOutstanding = ref(null)

const list = ref([])
const listStatus = ref('')
const listLoading = ref(false)

const resultVisible = ref(false)
const resultMo = ref(null)
const resultWarnings = ref([])
const resultDetail = ref({})
const resultTab = ref('bom')

const detailVisible = ref(false)
const detailMo = ref(null)
const detailBom = ref([])

async function loadPmos() {
  loading.value = true
  try {
    const res = await getCandidatePmosApi()
    pmos.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function searchProducts(q) {
  if (!q) { productOptions.value = []; return }
  searching.value = true
  try {
    const res = await request.get('/mdm/items', { params: { keyword: q, status: '1', pageNum: 1, pageSize: 20 } })
    productOptions.value = (res.data?.records || res.data || []).filter(i => i.purchaseType === 'MAKE')
  } finally {
    searching.value = false
  }
}

async function showResult(out) {
  resultMo.value = out.mo
  resultWarnings.value = out.warnings || []
  resultVisible.value = true
  resultTab.value = 'bom'
  const d = await getMoDetailApi(out.mo.id)
  resultDetail.value = d.data || {}
  await loadList()
  await loadPmos()
}

async function createManual() {
  if (!form.value.productCode) { ElMessage.warning('请选择产品'); return }
  if (!form.value.planEndDate) { ElMessage.warning('请选择计划完工日期'); return }
  saving.value = true
  try {
    const res = await createMoApi(form.value)
    await showResult(res.data)
    ElMessage.success('工单已创建（草稿/计划态）')
  } finally {
    saving.value = false
  }
}

async function createFromPmo(row) {
  saving.value = true
  try {
    const res = await createFromPmoApi(row.id)
    await showResult(res.data)
    ElMessage.success(`已从 ${row.pmoNo} 建单并回写关联`)
  } finally {
    saving.value = false
  }
}

async function loadList() {
  listLoading.value = true
  try {
    const res = await getMosApi({ status: listStatus.value || undefined })
    list.value = res.data || []
  } finally {
    listLoading.value = false
  }
}

async function openDetail(row) {
  const d = await getMoDetailApi(row.id)
  detailMo.value = d.data.mo
  detailBom.value = d.data.bomLines || []
  detailVisible.value = true
}

onMounted(() => { loadPmos(); loadList() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.tip { color: #909399; font-size: 12px; margin-left: 8px; }
.warn-line { color: #e6a23c; font-size: 12px; line-height: 1.8; }
</style>
