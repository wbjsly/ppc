<template>
  <div>
    <el-page-header content="外协对账（5.10.4）— 按外协 PO 汇总未对账收货明细生成对账单，审批通过生成应付挂账（金额=数量×PO 成交价，禁改价）"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-input v-model="filters.poNo" size="small" clearable placeholder="PO 号"
                style="width: 180px" @keyup.enter="load" />
      <el-input v-model="filters.supplierCode" size="small" clearable placeholder="供应商编码"
                style="width: 160px" @keyup.enter="load" />
      <el-select v-model="filters.status" size="small" clearable placeholder="状态" style="width: 140px">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="审批中" value="CONFIRMED" />
        <el-option label="已挂账" value="POSTED" />
      </el-select>
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-button size="small" type="success" @click="openGenerate">按 PO 生成</el-button>
      <el-button size="small" @click="tab = tab === 'settle' ? 'ap' : 'settle'">
        {{ tab === 'settle' ? '查看应付挂账' : '返回对账单' }}
      </el-button>
      <span class="tip">金额取 PO 成交价快照，不可人工改价；同一收货行至多进一张对账单</span>
    </div>

    <!-- 对账单 -->
    <template v-if="tab === 'settle'">
      <div v-for="g in grouped" :key="g.status" style="margin-bottom: 12px">
        <div class="group-title">
          <el-tag :type="stTag(g.status)" size="small">{{ stText(g.status) }}</el-tag>
          <span class="tip">{{ g.rows.length }} 张</span>
        </div>
        <el-table :data="g.rows" v-loading="loading" border stripe size="small">
          <el-table-column prop="osrNo" label="对账单号" width="180" />
          <el-table-column prop="poNo" label="PO 号" width="160" show-overflow-tooltip />
          <el-table-column label="供应商" width="190" show-overflow-tooltip>
            <template #default="{ row }">{{ row.supplierCode }}（{{ row.supplierName }}）</template>
          </el-table-column>
          <el-table-column label="对账数量" width="100" align="right">
            <template #default="{ row }">{{ row.totalQty }}</template>
          </el-table-column>
          <el-table-column label="对账金额" width="110" align="right">
            <template #default="{ row }">
              <span style="font-weight: 600">¥ {{ row.totalAmt }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="apprNo" label="审批单号" width="150" show-overflow-tooltip />
          <el-table-column label="挂账时间" width="150">
            <template #default="{ row }">{{ row.postDate || '-' }}</template>
          </el-table-column>
          <el-table-column prop="rejectReason" label="驳回原因" min-width="120" show-overflow-tooltip />
          <el-table-column label="操作" width="170" align="center" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openDetail(row)">明细</el-button>
              <el-button v-if="row.status === 'DRAFT'" size="small" type="success" link
                         @click="doSubmit(row)">提交审批</el-button>
              <el-button v-if="row.status === 'DRAFT'" size="small" type="danger" link
                         @click="doDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-empty v-if="!loading && rows.length === 0"
                description="暂无对账单（点「按 PO 生成」，或先在 5.10.1 完成外协收货）"
                :image-size="60" />
    </template>

    <!-- 应付挂账 -->
    <template v-else>
      <el-table :data="apRows" v-loading="loading" border stripe size="small">
        <el-table-column prop="apNo" label="挂账单号" width="180" />
        <el-table-column prop="osrNo" label="来源对账单" width="180" show-overflow-tooltip />
        <el-table-column label="供应商" width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierCode }}（{{ row.supplierName }}）</template>
        </el-table-column>
        <el-table-column label="挂账金额" width="120" align="right">
          <template #default="{ row }">
            <span style="font-weight: 600">¥ {{ row.amt }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag type="warning" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="postDate" label="挂账时间" width="150" />
      </el-table>
      <el-empty v-if="!loading && apRows.length === 0" description="暂无应付挂账（对账单审批通过后生成）"
                :image-size="60" />
    </template>

    <!-- 生成弹窗 -->
    <el-dialog v-model="genVisible" title="按 PO 生成对账单" width="520px">
      <el-form label-width="90px" size="small">
        <el-form-item label="外协 PO">
          <el-select v-model="genPo" filterable placeholder="选择已收货的外协 PO" style="width: 100%">
            <el-option v-for="p in poOptions" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <span class="tip">汇总该 PO 全部未对账收货过账明细；金额=数量×PO 成交价</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="genVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="doGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 明细下钻 -->
    <el-dialog v-model="detailVisible" title="对账单明细" width="860px">
      <el-descriptions :column="3" border size="small" style="margin-bottom: 10px">
        <el-descriptions-item label="对账单号">{{ detail.osr?.osrNo }}</el-descriptions-item>
        <el-descriptions-item label="PO 号">{{ detail.osr?.poNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="stTag(detail.osr?.status)" size="small">{{ stText(detail.osr?.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="供应商" :span="2">
          {{ detail.osr?.supplierCode }}（{{ detail.osr?.supplierName }}）
        </el-descriptions-item>
        <el-descriptions-item label="金额">¥ {{ detail.osr?.totalAmt }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="detail.lines || []" border size="small">
        <el-table-column prop="lineNo" label="#" width="46" align="center" />
        <el-table-column prop="grNo" label="收货单号" width="150" show-overflow-tooltip />
        <el-table-column prop="itemCode" label="物料" width="130" show-overflow-tooltip />
        <el-table-column prop="itemName" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column label="数量" width="90" align="right">
          <template #default="{ row }">{{ row.qty }}</template>
        </el-table-column>
        <el-table-column label="PO 单价" width="100" align="right">
          <template #default="{ row }">{{ row.unitPrice }}</template>
        </el-table-column>
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">
            <span style="font-weight: 600">¥ {{ row.amount }}</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteOsSettlementApi, generateOsSettlementApi, getOsApListApi,
  getOsSettlementDetailApi, getOsSettlementListApi, getWorkbenchApi,
  submitOsSettlementApi
} from '@/api/mrp/outsourcing'

const loading = ref(false)
const saving = ref(false)
const tab = ref('settle')
const rows = ref([])
const apRows = ref([])
const filters = reactive({ poNo: '', supplierCode: '', status: '' })

const genVisible = ref(false)
const genPo = ref('')
const poOptions = ref([])

const detailVisible = ref(false)
const detail = reactive({ osr: {}, lines: [] })

const stText = (s) => ({ DRAFT: '草稿', CONFIRMED: '审批中', POSTED: '已挂账', REJECTED: '已驳回' }[s] || s)
const stTag = (s) => ({ DRAFT: 'info', CONFIRMED: 'warning', POSTED: 'success', REJECTED: 'danger' }[s] || 'info')

// 状态分组（DRAFT → CONFIRMED → POSTED）
const grouped = computed(() => {
  const order = ['DRAFT', 'CONFIRMED', 'POSTED']
  return order
      .map((s) => ({ status: s, rows: rows.value.filter((r) => r.status === s) }))
      .filter((g) => g.rows.length > 0)
})

async function load() {
  loading.value = true
  try {
    const params = {}
    if (filters.poNo) params.poNo = filters.poNo
    if (filters.supplierCode) params.supplierCode = filters.supplierCode
    if (filters.status) params.status = filters.status
    const [s, a] = await Promise.all([
      getOsSettlementListApi(params),
      getOsApListApi(filters.supplierCode ? { supplierCode: filters.supplierCode } : {})
    ])
    rows.value = s.data || []
    apRows.value = a.data || []
  } finally {
    loading.value = false
  }
}

async function openGenerate() {
  genPo.value = ''
  genVisible.value = true
  if (!poOptions.value.length) {
    const wb = await getWorkbenchApi({})
    // 外协 PO 选项取自工作台（去重，只留已下单的）
    poOptions.value = [...new Set((wb.data || [])
        .map((d) => d.poNo)
        .filter((p) => p && p.trim()))]
  }
}

async function doGenerate() {
  if (!genPo.value) {
    ElMessage.warning('请选择外协 PO')
    return
  }
  saving.value = true
  try {
    const res = await generateOsSettlementApi(genPo.value)
    ElMessage.success(`对账单 ${res.data?.osrNo} 已生成（草稿）`)
    genVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  const res = await getOsSettlementDetailApi(row.id)
  detail.osr = res.data?.osr || {}
  detail.lines = res.data?.lines || []
  detailVisible.value = true
}

async function doSubmit(row) {
  try {
    await ElMessageBox.confirm(
        `确认提交对账单 ${row.osrNo}（金额 ¥${row.totalAmt}）走审批？提交后进入审批中不可修改。`,
        '提交审批', { type: 'warning' })
  } catch { return }
  saving.value = true
  try {
    await submitOsSettlementApi(row.id)
    ElMessage.success('已提交审批（计划主管签核）')
    await load()
  } finally {
    saving.value = false
  }
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm(
        `确认删除草稿 ${row.osrNo}？收货行引用将释放，可重新汇总生成。`, '删除', { type: 'warning' })
  } catch { return }
  await deleteOsSettlementApi(row.id)
  ElMessage.success('已删除（引用已释放）')
  await load()
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.group-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
</style>
