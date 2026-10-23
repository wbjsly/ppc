<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- ================= 7.6.1 报废申请 ================= -->
      <el-tab-pane label="报废申请" name="scrap">
        <el-form inline>
          <el-form-item label="状态">
            <el-select v-model="scrapQuery.status" clearable placeholder="全部" style="width:160px">
              <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-button type="primary" @click="loadScrap">查询</el-button>
          <el-button type="success" @click="openCreate('SCRAP')">新建报废单</el-button>
        </el-form>
        <el-table :data="scrapRows" border>
          <el-table-column prop="dspNo" label="处置单号" width="170" />
          <el-table-column prop="appStatus" label="状态" width="140">
            <template #default="{ row }">
              <el-tag :type="statusType(row.appStatus)">{{ row.appStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="totalCost" label="原值合计" width="120" />
          <el-table-column prop="totalNbv" label="NBV 合计" width="120" />
          <el-table-column prop="gainLoss" label="处置损益" width="120" />
          <el-table-column label="操作" width="260">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row.id)">明细</el-button>
              <el-button v-if="['DRAFT','REJECTED'].includes(row.appStatus)" size="small"
                         type="primary" @click="doSubmit(row.id)">提交审批</el-button>
              <el-button v-if="row.appStatus === 'APPROVED'" size="small" type="warning"
                         @click="openExec(row.id, 'SCRAP')">执行确认</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 7.6.2 资产出售 ================= -->
      <el-tab-pane label="资产出售" name="sale">
        <el-form inline>
          <el-button type="primary" @click="loadSale">查询</el-button>
          <el-button type="success" @click="openCreate('SALE')">新建出售单</el-button>
        </el-form>
        <el-table :data="saleRows" border>
          <el-table-column prop="dspNo" label="处置单号" width="170" />
          <el-table-column prop="appStatus" label="状态" width="140">
            <template #default="{ row }">
              <el-tag :type="statusType(row.appStatus)">{{ row.appStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="totalNbv" label="NBV 合计" width="120" />
          <el-table-column prop="gainLoss" label="处置损益" width="120" />
          <el-table-column prop="receivableBal" label="应收余额" width="120" />
          <el-table-column label="操作" width="320">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row.id)">明细</el-button>
              <el-button v-if="['DRAFT','REJECTED'].includes(row.appStatus)" size="small"
                         type="primary" @click="doSubmit(row.id)">提交审批</el-button>
              <el-button v-if="row.appStatus === 'APPROVED'" size="small" type="warning"
                         @click="openExec(row.id, 'SALE')">执行确认</el-button>
              <el-button v-if="['RECEIVABLE','EXECUTED'].includes(row.appStatus) && row.receivableBal > 0"
                         size="small" type="success" @click="openReceive(row.id)">收款登记</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 7.6.3 损益处理 ================= -->
      <el-tab-pane label="损益处理" name="pl">
        <el-form inline>
          <el-button type="primary" @click="loadPl">刷新</el-button>
        </el-form>
        <el-table :data="plRows" border>
          <el-table-column prop="dspNo" label="处置单号" width="170" />
          <el-table-column prop="disposalType" label="方式" width="80">
            <template #default="{ row }">{{ row.disposalType === 'SCRAP' ? '报废' : '出售' }}</template>
          </el-table-column>
          <el-table-column prop="appStatus" label="状态" width="130" />
          <el-table-column prop="totalNbv" label="NBV" width="110" />
          <el-table-column prop="gainLoss" label="处置损益" width="110" />
          <el-table-column prop="receivableBal" label="应收余额" width="110" />
          <el-table-column label="操作" width="300">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row.id)">凭证锚明细</el-button>
              <el-button v-if="['RECEIVABLE','EXECUTED'].includes(row.appStatus) && row.receivableBal > 0"
                         size="small" type="success" @click="openReceive(row.id)">收款登记</el-button>
              <el-button v-if="['EXECUTED','RECEIVABLE'].includes(row.appStatus)"
                         size="small" type="primary" @click="doSubmitReport(row.id)">结案报告</el-button>
              <el-button v-if="['EXECUTED','RECEIVABLE'].includes(row.appStatus)"
                         size="small" type="danger" @click="doReverse(row.id)">整单冲回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建处置单对话框 -->
    <el-dialog v-model="createVisible" :title="createForm.disposalType === 'SCRAP' ? '新建报废单' : '新建出售单'" width="880px">
      <el-form label-width="110px">
        <el-form-item label="处置原因">
          <el-select v-model="createForm.reasonCode" style="width:180px">
            <el-option label="技术淘汰" value="TECH" />
            <el-option label="损坏" value="DAMAGE" />
            <el-option label="闲置" value="IDLE" />
            <el-option label="政策性淘汰" value="POLICY" />
          </el-select>
          <el-input v-model="createForm.reasonNote" placeholder="原因说明" style="margin-left:8px" />
        </el-form-item>
        <el-form-item label="评估残值">
          <el-input-number v-model="createForm.estResidual" :min="0" :precision="2" />
          <span style="margin-left:8px;color:#909399">出售=市场价 / 报废=残料回收价；&gt; NBV×10% 须复核说明</span>
        </el-form-item>
        <el-form-item v-if="residualAlert" label="复核说明" required>
          <el-input v-model="createForm.reviewNote" placeholder="残值评估偏高，须填写复核说明（C-4.13-05）" />
        </el-form-item>
        <el-form-item label="评估报告号">
          <el-input v-model="createForm.evalReportNo" placeholder="出售且评估价&lt;NBV 时必填（BR-31）" />
        </el-form-item>
        <el-divider content-position="left">处置卡片（批量）</el-divider>
        <el-form-item label="选卡">
          <el-input v-model="cardKeyword" placeholder="输入卡号/名称搜索" style="width:260px" @keyup.enter="searchCards">
            <template #append><el-button @click="searchCards">搜索</el-button></template>
          </el-input>
        </el-form-item>
        <el-table :data="createLines" border size="small">
          <el-table-column prop="cardNo" label="卡号" width="130" />
          <el-table-column prop="assetName" label="名称" />
          <el-table-column label="原值" width="100">
            <template #default="{ row }">{{ row._cost }}</template>
          </el-table-column>
          <el-table-column label="NBV" width="100">
            <template #default="{ row }">{{ row._nbv }}</template>
          </el-table-column>
          <el-table-column v-if="createForm.disposalType === 'SALE'" label="处置价" width="140">
            <template #default="{ row }">
              <el-input-number v-model="row.lineProceeds" :min="0" :precision="2" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ $index }">
              <el-button size="small" type="danger" link @click="createLines.splice($index, 1)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-divider content-position="left">残料回收明细（可选；执行确认时一键入库）</el-divider>
        <el-table :data="createRecycles" border size="small">
          <el-table-column label="品名" width="180">
            <template #default="{ row }"><el-input v-model="row.itemName" size="small" /></template>
          </el-table-column>
          <el-table-column label="物料编码" width="160">
            <template #default="{ row }"><el-input v-model="row.itemCode" size="small" /></template>
          </el-table-column>
          <el-table-column label="数量" width="120">
            <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" size="small" /></template>
          </el-table-column>
          <el-table-column label="估价" width="130">
            <template #default="{ row }"><el-input-number v-model="row.estPrice" :min="0" :precision="2" size="small" /></template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ $index }">
              <el-button size="small" type="danger" link @click="createRecycles.splice($index, 1)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" style="margin:8px 0" @click="createRecycles.push({ itemName: '', itemCode: '', qty: 1, estPrice: 0 })">+ 加回收明细</el-button>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建草稿</el-button>
      </template>
    </el-dialog>

    <!-- 明细对话框 -->
    <el-dialog v-model="detailVisible" title="处置单明细" width="900px">
      <template v-if="detail.order">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="单号">{{ detail.order.dspNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.order.appStatus }}</el-descriptions-item>
          <el-descriptions-item label="方式">{{ detail.order.disposalType === 'SCRAP' ? '报废' : '出售' }}</el-descriptions-item>
          <el-descriptions-item label="原值合计">{{ detail.order.totalCost }}</el-descriptions-item>
          <el-descriptions-item label="NBV 合计">{{ detail.order.totalNbv }}</el-descriptions-item>
          <el-descriptions-item label="处置价">{{ detail.order.totalProceeds }}</el-descriptions-item>
          <el-descriptions-item label="回收合计">{{ detail.order.totalRecycle }}</el-descriptions-item>
          <el-descriptions-item label="处置损益">{{ detail.order.gainLoss }}</el-descriptions-item>
          <el-descriptions-item label="应收余额">{{ detail.order.receivableBal }}</el-descriptions-item>
          <el-descriptions-item label="评估残值">{{ detail.order.estResidual }}</el-descriptions-item>
          <el-descriptions-item label="复核说明" :span="2">{{ detail.order.reviewNote || '—' }}</el-descriptions-item>
          <el-descriptions-item label="执行凭证" :span="3">{{ detail.order.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item label="结案报告" :span="3">{{ detail.order.reportSummary || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">处置明细（凭证锚 DISPOSAL_TRANSFER/PROCEEDS/RECYCLE/GAINLOSS）</el-divider>
        <el-table :data="detail.lines" border size="small">
          <el-table-column prop="cardNo" label="卡号" width="130" />
          <el-table-column prop="assetName" label="名称" />
          <el-table-column prop="origCost" label="原值" width="100" />
          <el-table-column prop="accumDepr" label="累计折旧" width="100" />
          <el-table-column prop="nbv" label="NBV" width="100" />
          <el-table-column prop="lineProceeds" label="处置价" width="100" />
        </el-table>
        <el-table v-if="detail.recycles && detail.recycles.length" :data="detail.recycles" border size="small" style="margin-top:8px">
          <el-table-column prop="itemName" label="回收物" width="160" />
          <el-table-column prop="itemCode" label="物料编码" width="140" />
          <el-table-column prop="qty" label="数量" width="80" />
          <el-table-column prop="estPrice" label="估价" width="100" />
          <el-table-column prop="grId" label="入库单" width="200" />
          <el-table-column prop="grStatus" label="入库状态" width="100" />
        </el-table>
      </template>
    </el-dialog>

    <!-- 执行确认对话框 -->
    <el-dialog v-model="execVisible" title="执行确认" width="560px">
      <el-form label-width="130px">
        <el-form-item :label="execType === 'SCRAP' ? '报废证明编号' : '买卖合同编号'" required>
          <el-input v-model="execForm.proofNo" />
        </el-form-item>
        <el-form-item label="环保安全评估号">
          <el-input v-model="execForm.envAssessNo" placeholder="分类 ENV_REVIEW_FLAG=1 时必填（BR-36）" />
        </el-form-item>
        <el-form-item label="数据销毁证明号">
          <el-input v-model="execForm.dataDestroyNo" placeholder="分类 DATA_DESTROY_FLAG=1 时必填（BR-36）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="execVisible = false">取消</el-button>
        <el-button type="warning" @click="doExec">确认执行（生成段2/3凭证+卡片已处置）</el-button>
      </template>
    </el-dialog>

    <!-- 收款登记对话框 -->
    <el-dialog v-model="receiveVisible" title="收款登记（BR-35）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="收款金额">
          <el-input-number v-model="receiveForm.amount" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="receiveForm.note" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="receiveVisible = false">取消</el-button>
        <el-button type="success" @click="doReceive">登记收款</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  disposalPage, disposalDetail, disposalCreate, disposalSubmit, disposalExec,
  disposalReceive, disposalSubmitReport, disposalReverse, cardSearch
} from '@/api/eam/disposal'

const route = useRoute()
const tab = ref(route.meta?.tab || 'scrap')
const STATUSES = ['DRAFT', 'PENDING_APPROVE', 'APPROVED', 'EXECUTED', 'RECEIVABLE', 'CLOSED', 'REJECTED']

const scrapQuery = ref({ status: '' })
const scrapRows = ref([])
const saleRows = ref([])
const plRows = ref([])

const statusType = (s) => ({
  DRAFT: 'info', PENDING_APPROVE: 'warning', APPROVED: 'primary',
  EXECUTED: '', RECEIVABLE: 'warning', CLOSED: 'success', REJECTED: 'danger'
}[s] || 'info')

async function loadScrap() {
  const res = await disposalPage({ type: 'SCRAP', status: scrapQuery.value.status, size: 50 })
  scrapRows.value = res.data.records
}
async function loadSale() {
  const res = await disposalPage({ type: 'SALE', size: 50 })
  saleRows.value = res.data.records
}
async function loadPl() {
  const res = await disposalPage({ size: 50 })
  plRows.value = res.data.records
}
onMounted(() => { loadScrap(); loadSale(); loadPl() })

// ---------- 新建 ----------
const createVisible = ref(false)
const createForm = ref({})
const createLines = ref([])
const createRecycles = ref([])
const cardKeyword = ref('')

const residualAlert = computed(() => {
  const nbv = createLines.value.reduce((s, l) => s + (Number(l._nbv) || 0), 0)
  return nbv > 0 && (Number(createForm.value.estResidual) || 0) > nbv * 0.1
})

function openCreate(type) {
  createForm.value = { disposalType: type, reasonCode: 'TECH', reasonNote: '', estResidual: 0, evalReportNo: '', reviewNote: '' }
  createLines.value = []
  createRecycles.value = []
  cardKeyword.value = ''
  createVisible.value = true
}

async function searchCards() {
  if (!cardKeyword.value) return
  const res = await cardSearch(cardKeyword.value)
  const records = res.data.records || []
  for (const c of records) {
    if (createLines.value.some(l => l.cardId === c.id)) continue
    if (c.status !== 'IN_USE') continue
    createLines.value.push({
      cardId: c.id, cardNo: c.cardNo, assetName: c.assetName,
      _cost: c.assetCost, _nbv: (Number(c.assetCost) || 0) - (Number(c.accumDepr) || 0),
      lineProceeds: 0
    })
  }
}

async function doCreate() {
  const payload = {
    order: createForm.value,
    lines: createLines.value.map(l => ({ cardId: l.cardId, lineProceeds: l.lineProceeds })),
    recycles: createRecycles.value.filter(r => r.itemName)
  }
  const res = await disposalCreate(payload)
  ElMessage.success(`处置单 ${res.data.order.dspNo} 已创建（${res.data.lineCount} 张卡）`)
  createVisible.value = false
  loadScrap(); loadSale(); loadPl()
}

// ---------- 提交审批 ----------
async function doSubmit(id) {
  const res = await disposalSubmit(id)
  ElMessage.success(`已提交（${res.data.nodes} 节点链 ${res.data.tier}）`)
  loadScrap(); loadSale()
}

// ---------- 执行确认 ----------
const execVisible = ref(false)
const execId = ref('')
const execType = ref('SCRAP')
const execForm = ref({})

function openExec(id, type) {
  execId.value = id
  execType.value = type
  execForm.value = { proofNo: '', envAssessNo: '', dataDestroyNo: '' }
  execVisible.value = true
}
async function doExec() {
  if (!execForm.value.proofNo) { ElMessage.warning('执行凭证编号必填'); return }
  const res = await disposalExec(execId.value, execForm.value)
  ElMessage.success(`执行完成：损益 ${res.data.gainLoss}，状态 ${res.data.status}，回收入库 ${res.data.recyclePosted} 条`)
  execVisible.value = false
  loadScrap(); loadSale(); loadPl()
}

// ---------- 收款 ----------
const receiveVisible = ref(false)
const receiveId = ref('')
const receiveForm = ref({ amount: 0, note: '' })
function openReceive(id) {
  receiveId.value = id
  receiveForm.value = { amount: 0, note: '' }
  receiveVisible.value = true
}
async function doReceive() {
  const res = await disposalReceive(receiveId.value, receiveForm.value)
  ElMessage.success(`收款成功，应收余额 ${res.data.receivableBal}`)
  receiveVisible.value = false
  loadSale(); loadPl()
}

// ---------- 明细 ----------
const detailVisible = ref(false)
const detail = ref({})
async function openDetail(id) {
  const res = await disposalDetail(id)
  detail.value = res.data
  detailVisible.value = true
}

// ---------- 结案 / 冲回 ----------
async function doSubmitReport(id) {
  const res = await disposalSubmitReport(id)
  ElMessage.success('结案报告已提交（财务资产会计单签）')
  loadPl(); loadSale()
}
async function doReverse(id) {
  await ElMessageBox.confirm('整单冲回将逆序冲回全部处置凭证并恢复卡片，库存入库单不自动冲销。继续？', '整单冲回', { type: 'warning' })
  const res = await disposalReverse(id)
  ElMessage.success(`已冲回 ${res.data.reversedVouchers} 张凭证`)
  loadScrap(); loadSale(); loadPl()
}
</script>

<style scoped>
.page { padding: 8px; }
</style>
