<template>
  <div class="page">
    <div class="head">
      <span class="title">出口退税（一票一档 → 单证检查 → 退税计算 → 状态跟踪 → 到账核销）</span>
      <div>
        <el-button size="small" @click="load">刷新</el-button>
        <el-button type="primary" size="small" @click="openRegisterDeal">登记一票一档</el-button>
        <el-button type="warning" size="small" @click="doWarnings">分级预警</el-button>
        <el-button type="success" size="small" @click="openCreateApply">创建退税申报单</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" class="mb8"
      title="数据为手工登记代理口径（电子口岸/收汇系统不直连）；CIF/CFR 自动扣运保费佣金折算 FOB（BR-41）；单证缺一阻断（L2 豁免留痕）" />

    <el-tabs v-model="tab">
      <el-tab-pane label="一票一档" name="deals">
        <el-table :data="deals" size="small" border>
          <el-table-column prop="dealNo" label="档案号" width="150" />
          <el-table-column prop="invoiceNo" label="出口发票" width="120" />
          <el-table-column prop="declNo" label="报关单号" width="130" />
          <el-table-column prop="declDate" label="报关日" width="100" />
          <el-table-column prop="tradeTerm" label="成交" width="60" />
          <el-table-column prop="fobCny" label="FOB 折算" width="100" />
          <el-table-column label="退税率" width="70">
            <template #default="{ row }">{{ row.refundRate ? (Number(row.refundRate) * 100).toFixed(0) + '%' : '—' }}</template>
          </el-table-column>
          <el-table-column prop="refundTax" label="应退税额" width="100" />
          <el-table-column label="三单" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.docDecl === '1' && row.docInvoice === '1' && row.docReceipt === '1'" type="success" size="small">齐全</el-tag>
              <el-tag v-else type="danger" size="small">缺 {{ 3 - [row.docDecl, row.docInvoice, row.docReceipt].filter(x => x === '1').length }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="预警" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.mismatchFlag === '1'" type="warning" size="small">不一致</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="expireDate" label="申报截止" width="100" />
          <el-table-column prop="refundApplyNo" label="申报单" width="140" />
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <el-button link type="success" size="small" @click="openCollect(row)">单证收齐</el-button>
              <el-button link type="info" size="small" @click="doCheckDocs(row)">单证检查</el-button>
              <el-button link type="warning" size="small" @click="openExempt(row)">豁免</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="退税申报单" name="applies">
        <el-table :data="applies" size="small" border>
          <el-table-column prop="applyNo" label="申报单号" width="160" />
          <el-table-column prop="period" label="所属期" width="80" />
          <el-table-column prop="dealCount" label="票数" width="60" />
          <el-table-column prop="fobTotal" label="FOB 合计" width="110" />
          <el-table-column prop="refundTotal" label="应退合计" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="applyTag(row.status)" size="small">{{ applyText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="approvedAmount" label="核准额" width="100" />
          <el-table-column prop="receivedAmount" label="到账" width="100" />
          <el-table-column label="操作" width="260">
            <template #default="{ row }">
              <el-button v-if="['SUBMITTED','ACCEPTED','AUDITING'].includes(row.status)" link type="primary" size="small"
                @click="openRegisterApply(row)">状态登记</el-button>
              <el-button v-if="row.status === 'APPROVED'" link type="success" size="small" @click="openReceive(row)">到账核销</el-button>
              <el-button link type="info" size="small" @click="openApplyDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 登记一票一档 -->
    <el-dialog v-model="dealVisible" title="一票一档登记（离线代理口径）" width="560px">
      <el-form label-width="110px" size="small">
        <el-form-item label="出口发票号" required><el-input v-model="dealForm.invoiceNo" /></el-form-item>
        <el-form-item label="报关单号" required><el-input v-model="dealForm.declNo" /></el-form-item>
        <el-form-item label="报关日期" required><el-date-picker v-model="dealForm.declDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="成交方式">
          <el-select v-model="dealForm.tradeTerm" style="width: 140px">
            <el-option label="FOB" value="FOB" /><el-option label="CIF" value="CIF" /><el-option label="CFR" value="CFR" />
          </el-select>
        </el-form-item>
        <el-form-item label="成交价（原币）" required><el-input-number v-model="dealForm.grossAmount" :precision="2" /></el-form-item>
        <el-form-item label="运费/保费/佣金">
          <el-input-number v-model="dealForm.freight" :precision="2" style="width: 130px" />
          <el-input-number v-model="dealForm.insurance" :precision="2" style="width: 130px; margin: 0 6px" />
          <el-input-number v-model="dealForm.commission" :precision="2" style="width: 130px" />
        </el-form-item>
        <el-form-item label="币别/汇率">
          <el-input v-model="dealForm.currency" style="width: 90px" />
          <el-input-number v-model="dealForm.fxRate" :precision="6" :step="0.01" style="width: 160px; margin-left: 6px" />
        </el-form-item>
        <el-form-item label="商品大类">
          <el-input v-model="dealForm.itemCategory" placeholder="退税率 EXPORT_REFUND_RATE_<大类>" style="width: 240px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dealVisible = false">取消</el-button>
        <el-button type="primary" @click="doRegisterDeal">登记</el-button>
      </template>
    </el-dialog>

    <!-- 单证收齐 -->
    <el-dialog v-model="collectVisible" title="单证收齐登记（BR-4.14-03 三单）" width="440px">
      <el-form label-width="110px" size="small">
        <el-form-item label="报关单"><el-switch v-model="collectForm.docDecl" /></el-form-item>
        <el-form-item label="出口发票"><el-switch v-model="collectForm.docInvoice" /></el-form-item>
        <el-form-item label="收汇凭证"><el-switch v-model="collectForm.docReceipt" /></el-form-item>
        <el-form-item v-if="collectForm.docReceipt" label="收汇日期"><el-date-picker v-model="collectForm.receiptDate" value-format="YYYY-MM-DD" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="collectVisible = false">取消</el-button>
        <el-button type="primary" @click="doCollect">登记</el-button>
      </template>
    </el-dialog>

    <!-- 豁免 -->
    <el-dialog v-model="exemptVisible" title="单证豁免登记（L2：税务主管确认留痕）" width="480px">
      <el-form label-width="80px" size="small">
        <el-form-item label="豁免原因" required>
          <el-input v-model="exemptForm.reason" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exemptVisible = false">取消</el-button>
        <el-button type="warning" @click="doExempt">登记豁免</el-button>
      </template>
    </el-dialog>

    <!-- 创建申报单 -->
    <el-dialog v-model="applyVisible" title="创建退税申报单（聚合多票；单证门禁）" width="520px">
      <el-form label-width="130px" size="small">
        <el-form-item label="所属期 yyyyMM"><el-input v-model="applyForm.period" style="width: 140px" /></el-form-item>
        <el-form-item label="一票一档（多选）">
          <el-select v-model="applyForm.dealIds" multiple style="width: 100%">
            <el-option v-for="d in eligibleDeals" :key="d.id" :label="`${d.dealNo}｜应退 ${d.refundTax}`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="免抵退勾稽（可选）">
          <el-input-number v-model="applyForm.mtdPayable" :precision="2" placeholder="当期应纳税额" style="width: 160px" />
          <el-input v-model="applyForm.vatReturnId" placeholder="同期增值税申报单 ID" style="width: 200px; margin-left: 6px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreateApply">创建</el-button>
      </template>
    </el-dialog>

    <!-- 状态登记 -->
    <el-dialog v-model="applyRegisterVisible" title="申报状态登记（受理/审核/核准/退回补正/驳回）" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="目标状态">
          <el-select v-model="applyRegisterForm.status" style="width: 200px">
            <el-option label="受理 ACCEPTED" value="ACCEPTED" />
            <el-option label="审核中 AUDITING" value="AUDITING" />
            <el-option label="核准 APPROVED" value="APPROVED" />
            <el-option label="退回补正 RETURNED" value="RETURNED" />
            <el-option label="驳回 REJECTED" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="applyRegisterForm.status === 'APPROVED'" label="核准退税额">
          <el-input-number v-model="applyRegisterForm.approvedAmount" :precision="2" />
        </el-form-item>
        <el-form-item v-if="applyRegisterForm.status === 'RETURNED'" label="退回原因" required>
          <el-input v-model="applyRegisterForm.returnReason" type="textarea" :rows="2" placeholder="将生成结构化补正清单并挂起（暂停后续批次）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyRegisterVisible = false">取消</el-button>
        <el-button type="primary" @click="doApplyRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- 到账核销 -->
    <el-dialog v-model="receiveVisible" title="退税款到账（BR-45：一致核销；差异挂账核查）" width="440px">
      <el-form label-width="100px" size="small">
        <el-form-item label="到账金额" required><el-input-number v-model="receiveForm.receivedAmount" :precision="2" /></el-form-item>
        <el-form-item label="到账日期"><el-date-picker v-model="receiveForm.receivedDate" value-format="YYYY-MM-DD" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="receiveVisible = false">取消</el-button>
        <el-button type="primary" @click="doReceive">登记到账</el-button>
      </template>
    </el-dialog>

    <!-- 预警 -->
    <el-dialog v-model="warningsVisible" title="分级预警（截止前 60/30 天 + 超期清单）" width="600px">
      <template v-if="warnings">
        <el-alert v-if="warnings.overdueCount > 0" type="error" :closable="false" class="mb8"
          :title="`超期未申报 ${warnings.overdueCount} 票（C-4.14-13：阻断并评估视同内销）`" />
        <el-alert v-if="warnings.level2Count > 0" type="warning" :closable="false" class="mb8"
          :title="`二级预警：截止前 ${warnings.level2Days} 天内 ${warnings.level2Count} 票`" />
        <el-alert v-if="warnings.level1Count > 0" type="info" :closable="false" class="mb8"
          :title="`一级预警：截止前 ${warnings.level1Days} 天内 ${warnings.level1Count} 票`" />
        <el-alert v-if="!warnings.overdueCount && !warnings.level1Count" type="success" :closable="false"
          title="暂无预警" />
      </template>
    </el-dialog>

    <!-- 申报单详情 -->
    <el-drawer v-model="applyDetailVisible" :title="`退税申报详情 ${applyDetailRow ? applyDetailRow.applyNo : ''}`" size="520px">
      <template v-if="applyDetail">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="票数">{{ applyDetail.apply.dealCount }}</el-descriptions-item>
          <el-descriptions-item label="应退合计">{{ applyDetail.apply.refundTotal }}</el-descriptions-item>
          <el-descriptions-item label="核准额">{{ applyDetail.apply.approvedAmount || '—' }}</el-descriptions-item>
          <el-descriptions-item label="到账">{{ applyDetail.apply.receivedAmount || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">所辖一票一档</el-divider>
        <el-table :data="applyDetail.deals" size="small" border max-height="240">
          <el-table-column prop="dealNo" label="档案号" width="140" />
          <el-table-column prop="declNo" label="报关单号" width="130" />
          <el-table-column prop="refundTax" label="应退税额" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  expRegisterDeal, expDealPage, expCheckDocs, expCollectDocs, expExemption,
  expCreateApply, expSubmit, expRegister, expReceive, expApplyPage, expApplyDetail, expWarnings
} from '@/api/fin/tax-filing'

const tab = ref('deals')
const deals = ref([])
const applies = ref([])
const dealVisible = ref(false)
const dealForm = ref({})
const collectVisible = ref(false)
const collectRow = ref(null)
const collectForm = ref({})
const exemptVisible = ref(false)
const exemptRow = ref(null)
const exemptForm = ref({})
const applyVisible = ref(false)
const applyForm = ref({})
const applyRegisterVisible = ref(false)
const applyRegisterRow = ref(null)
const applyRegisterForm = ref({})
const receiveVisible = ref(false)
const receiveRow = ref(null)
const receiveForm = ref({})
const warningsVisible = ref(false)
const warnings = ref(null)
const applyDetailVisible = ref(false)
const applyDetailRow = ref(null)
const applyDetail = ref(null)

const eligibleDeals = computed(() =>
  deals.value.filter(d => !d.refundApplyNo && d.docDecl === '1' && d.docInvoice === '1' && d.docReceipt === '1'))

async function load() {
  try {
    const [{ data: d }, { data: a }] = await Promise.all([expDealPage({ size: 200 }), expApplyPage({ size: 200 })])
    deals.value = d.records || d
    applies.value = a.records || a
  } catch (e) { /* ignore */ }
}

function openRegisterDeal() {
  dealForm.value = { invoiceNo: '', declNo: '', declDate: '', tradeTerm: 'FOB', grossAmount: undefined,
    freight: 0, insurance: 0, commission: 0, currency: 'USD', fxRate: undefined, itemCategory: '' }
  dealVisible.value = true
}

async function doRegisterDeal() {
  try {
    const { data } = await expRegisterDeal(dealForm.value)
    ElMessage.success(`档案已登记：${data.dealNo}（FOB ${data.fobCny}，应退 ${data.refundTax}${data.mismatch ? '，⚠ 报关/发票不一致待核实' : ''}）`)
    dealVisible.value = false
    load()
  } catch (e) { /* 422 已提示 */ }
}

function openCollect(row) {
  collectRow.value = row
  collectForm.value = { docDecl: row.docDecl === '1', docInvoice: row.docInvoice === '1',
    docReceipt: row.docReceipt === '1', receiptDate: '' }
  collectVisible.value = true
}

async function doCollect() {
  try {
    await expCollectDocs(collectRow.value.id, collectForm.value)
    ElMessage.success('单证登记完成')
    collectVisible.value = false
    load()
  } catch (e) { /* ignore */ }
}

async function doCheckDocs(row) {
  try {
    const { data } = await expCheckDocs(row.id)
    if (!data.blocked) {
      ElMessage.success(`单证检查通过${data.exempted ? '（含 L2 豁免）' : ''}`)
    } else if (data.deemedSaleNote) {
      ElMessage.error(data.deemedSaleNote)
    } else {
      ElMessage.warning(`单证不齐，缺 ${data.missing.join('、')}${data.warning ? '；' + data.warning : ''}`)
    }
  } catch (e) { /* ignore */ }
}

function openExempt(row) {
  exemptRow.value = row
  exemptForm.value = { reason: '' }
  exemptVisible.value = true
}

async function doExempt() {
  try {
    await expExemption(exemptRow.value.id, exemptForm.value.reason)
    ElMessage.success('豁免已登记（留痕）')
    exemptVisible.value = false
    load()
  } catch (e) { /* ignore */ }
}

function openCreateApply() {
  applyForm.value = { period: new Date().toISOString().slice(0, 7).replace('-', ''), dealIds: [], mtdPayable: undefined, vatReturnId: '' }
  applyVisible.value = true
}

async function doCreateApply() {
  try {
    const { data } = await expCreateApply({ ...applyForm.value, dealIds: applyForm.value.dealIds.join(',') })
    ElMessage.success(`退税申报单已创建：${data.applyNo}（应退 ${data.refundTotal}，请提交审批）`)
    applyVisible.value = false
    tab.value = 'applies'
    load()
    const created = { id: data.id }
    await expSubmit(created.id)
    ElMessage.success('已提交 L2 审批')
    load()
  } catch (e) { /* 422 已提示 */ }
}

function openRegisterApply(row) {
  applyRegisterRow.value = row
  applyRegisterForm.value = { status: 'ACCEPTED', approvedAmount: undefined, returnReason: '' }
  applyRegisterVisible.value = true
}

async function doApplyRegister() {
  try {
    await expRegister(applyRegisterRow.value.id, applyRegisterForm.value)
    ElMessage.success('状态已登记')
    applyRegisterVisible.value = false
    load()
  } catch (e) { /* ignore */ }
}

function openReceive(row) {
  receiveRow.value = row
  receiveForm.value = { receivedAmount: row.approvedAmount, receivedDate: '' }
  receiveVisible.value = true
}

async function doReceive() {
  try {
    const { data } = await expReceive(receiveRow.value.id, receiveForm.value)
    if (data.reconciled) {
      ElMessage.success(`到账一致，已自动核销（凭证 ${data.voucherId ? '已生成' : ''}）`)
    } else {
      ElMessage.warning(`到账差异 ${data.diff}，已挂账核查不核销（BR-45）`)
    }
    receiveVisible.value = false
    load()
  } catch (e) { /* ignore */ }
}

async function doWarnings() {
  try {
    const { data } = await expWarnings()
    warnings.value = data
    warningsVisible.value = true
  } catch (e) { /* ignore */ }
}

async function openApplyDetail(row) {
  applyDetailRow.value = row
  applyDetail.value = null
  applyDetailVisible.value = true
  try {
    const { data } = await expApplyDetail(row.id)
    applyDetail.value = data
  } catch (e) { /* ignore */ }
}

function applyText(s) {
  return { DRAFT: '草稿', SUBMITTED: '已提交', ACCEPTED: '受理', AUDITING: '审核中',
    APPROVED: '已核准', PAID: '已核销', RETURNED: '退回补正', REJECTED: '驳回' }[s] || s
}
function applyTag(s) {
  return { DRAFT: 'info', SUBMITTED: 'primary', ACCEPTED: 'primary', AUDITING: 'warning',
    APPROVED: 'success', PAID: 'success', RETURNED: 'danger', REJECTED: 'danger' }[s] || 'info'
}

onMounted(load)
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.title { font-size: 15px; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; }
</style>
