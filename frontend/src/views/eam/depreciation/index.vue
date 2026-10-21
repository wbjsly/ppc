<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      :title="tab === 'run' ? '月度计提（7.2.1）：手动执行本月折旧，同事务出凭证+回写台账'
        : tab === 'change' ? '变动处理（7.2.2）：政策变更下期生效不追溯 / 减值次月重算（BR-4.13-14/15）'
        : '凭证生成（7.2.3）：折旧凭证与明细双向反查，勾稽差异'"
      :description="tab === 'run'
        ? 'PERIOD_TAG 幂等卡控（同月已提 422，先冲回）；三方法 SL/DDB/SYD；成本中心→科目分摊；BR-4.13-13 勾稽。'
        : tab === 'change'
        ? '政策变更/减值均单签 ROLE_FIN_ASSET；冲回为整单全冲（红冲凭证+台账回滚+放行重提）。'
        : '凭证 sourceType=DEPRECIATION，来源单号=run 单号，明细↔凭证双向可反查。'" />

    <!-- ================= 7.2.1 月度计提 ================= -->
    <template v-if="tab === 'run'">
      <el-card shadow="never">
        <div class="toolbar">
          <el-input v-model="periodTag" placeholder="会计月 yyyyMM" style="width:140px;" clearable />
          <el-button type="primary" @click="loadPreview">应提预览</el-button>
          <el-button v-if="canRun" type="success" :loading="running" @click="doRun">执行本月计提</el-button>
          <span class="spacer" />
          <el-button v-if="canRun && detail.run" type="danger" plain size="small"
            @click="doReverse(detail.run)">冲回本单</el-button>
        </div>

        <el-alert v-if="preview.missingParams && preview.missingParams.length" type="warning"
          :closable="false" style="margin:8px 0;"
          :title="`参数缺失告警（${preview.missingParams.length} 项）：${preview.missingParams.join('；')}`" />
        <el-alert v-if="preview.alreadyRun" type="error" :closable="false" style="margin:8px 0;"
          title="该会计月已存在有效计提单，如需重算请先冲回（PERIOD_TAG 幂等卡控）" />

        <el-table :data="preview.cards || []" size="small" border v-loading="loading" max-height="320"
          v-if="preview.cards && preview.cards.length" style="margin-top:8px;">
          <el-table-column prop="cardNo" label="卡片号" width="150" />
          <el-table-column prop="assetName" label="资产名称" min-width="140" />
          <el-table-column prop="method" label="折旧方法" width="90" align="center">
            <template #default="{ row }">{{ methodLabel(row.method) }}</template>
          </el-table-column>
          <el-table-column label="期初NBV" width="120" align="right">
            <template #default="{ row }">¥ {{ fmt(row.beginNbv) }}</template>
          </el-table-column>
          <el-table-column label="本期折旧" width="120" align="right">
            <template #default="{ row }"><b>¥ {{ fmt(row.periodDepreciation) }}</b></template>
          </el-table-column>
          <el-table-column label="期末NBV" width="120" align="right">
            <template #default="{ row }">¥ {{ fmt(row.endNbv) }}</template>
          </el-table-column>
          <el-table-column label="残值预警" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.residualAlert" size="small" type="warning">预警</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="提足截减" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.capped" size="small" type="danger">截减</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="preview.cards && preview.cards.length" style="margin-top:8px;text-align:right;">
          预计折旧合计：<b style="color:#409EFF;">¥ {{ fmt(preview.totalDepreciation) }}</b>
        </div>
      </el-card>

      <el-card shadow="never" style="margin-top:12px;">
        <el-table :data="runRows" size="small" border v-loading="loading">
          <el-table-column prop="runNo" label="计提单号" width="150">
            <template #default="{ row }"><b>{{ row.runNo }}</b></template>
          </el-table-column>
          <el-table-column prop="periodTag" label="会计月" width="90" align="center" />
          <el-table-column label="本期折旧" width="120" align="right">
            <template #default="{ row }">¥ {{ fmt(row.totalDepr) }}</template>
          </el-table-column>
          <el-table-column label="勾稽差异" width="110" align="right">
            <template #default="{ row }">
              <span :style="{ color: Number(row.reconDiff) > 0 ? '#F56C6C' : '' }">¥ {{ fmt(row.reconDiff) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="warningCount" label="残值预警" width="90" align="center" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.reversedFlag === '1' ? 'info' : 'success'">
                {{ row.reversedFlag === '1' ? '已冲回' : '已过账' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openRunDetail(row)">明细</el-button>
              <el-button v-if="canRun && row.reversedFlag !== '1'" link type="danger" size="small"
                @click="doReverse(row)">冲回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 计提单明细 -->
      <el-card shadow="never" style="margin-top:12px;" v-if="detail.run">
        <template #header><span>计提明细 —— {{ detail.run.runNo }}（{{ detail.run.periodTag }}）</span></template>
        <el-table :data="detail.details || []" size="small" border max-height="360">
          <el-table-column prop="cardNo" label="卡片号" width="150" />
          <el-table-column prop="assetName" label="资产名称" min-width="130" />
          <el-table-column label="本期折旧" width="110" align="right">
            <template #default="{ row }">¥ {{ fmt(row.periodDepr) }}</template>
          </el-table-column>
          <el-table-column label="期末累计" width="110" align="right">
            <template #default="{ row }">¥ {{ fmt(row.endAccumDepr) }}</template>
          </el-table-column>
          <el-table-column label="期末NBV" width="110" align="right">
            <template #default="{ row }">¥ {{ fmt(row.endNbv) }}</template>
          </el-table-column>
          <el-table-column prop="debitAccount" label="借方科目" width="100" align="center" />
          <el-table-column label="冲回" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.reversed === '1'" size="small" type="info">已冲回</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- ================= 7.2.2 变动处理 ================= -->
    <template v-else-if="tab === 'change'">
      <el-card shadow="never">
        <div class="toolbar">
          <el-select v-model="changeType" placeholder="类型" clearable style="width:150px;" @change="loadChanges">
            <el-option label="政策变更" value="POLICY" />
            <el-option label="减值" value="IMPAIRMENT" />
            <el-option label="冲回" value="REVERSE" />
          </el-select>
          <el-button type="primary" @click="loadChanges">查询</el-button>
          <span class="spacer" />
          <el-button v-if="canChange" type="success" @click="policyVisible = true">政策变更</el-button>
          <el-button v-if="canChange" type="warning" plain @click="impairVisible = true">减值</el-button>
        </div>

        <el-table :data="changeRows" size="small" border v-loading="loading">
          <el-table-column prop="changeNo" label="变动单号" width="170">
            <template #default="{ row }"><b>{{ row.changeNo }}</b></template>
          </el-table-column>
          <el-table-column label="类型" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ POLICY: 'primary', IMPAIRMENT: 'warning', REVERSE: 'info' }[row.changeType]">
                {{ { POLICY: '政策变更', IMPAIRMENT: '减值', REVERSE: '冲回' }[row.changeType] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="cardNo" label="卡片号" width="150" />
          <el-table-column label="变更前 → 后" min-width="170">
            <template #default="{ row }">
              <template v-if="row.changeType === 'POLICY'">
                年限 {{ row.beforeLifeYears }}→{{ row.afterLifeYears }}；
                残值率 {{ pct(row.beforeResidualRate) }}→{{ pct(row.afterResidualRate) }}
              </template>
              <template v-else-if="row.changeType === 'IMPAIRMENT'">减值 ¥ {{ fmt(row.impairAmount) }}</template>
              <template v-else>整单冲回</template>
            </template>
          </el-table-column>
          <el-table-column prop="effectivePeriod" label="生效期间" width="90" align="center" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ PENDING_APPROVE: 'warning', APPROVED: 'success', REJECTED: 'danger', DONE: 'info' }[row.status]">
                {{ { PENDING_APPROVE: '待审批', APPROVED: '已通过', REJECTED: '已驳回', DONE: '已生效' }[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" align="center">
            <template #default="{ row }">
              <template v-if="canApprove && row.status === 'PENDING_APPROVE'">
                <el-button link type="success" size="small" @click="doApprove(row)">通过</el-button>
                <el-button link type="danger" size="small" @click="doReject(row)">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 政策变更对话框 -->
      <el-dialog v-model="policyVisible" title="折旧政策变更（BR-4.13-14：下期生效不追溯）" width="480px">
        <el-form label-width="110px" size="small">
          <el-form-item label="卡片ID" required>
            <el-input v-model="policyForm.cardId" placeholder="资产卡片 ID" />
          </el-form-item>
          <el-form-item label="变更后年限">
            <el-input-number v-model="policyForm.afterLifeYears" :min="1" :max="60" />
          </el-form-item>
          <el-form-item label="变更后残值率">
            <el-input-number v-model="policyForm.afterResidualRate" :min="0" :max="1" :step="0.01" :precision="4" />
          </el-form-item>
          <el-form-item label="变更后方法">
            <el-select v-model="policyForm.afterMethod" style="width:100%;">
              <el-option label="直线法" value="STRAIGHT_LINE" />
              <el-option label="双倍余额递减" value="DOUBLE_DECLINING" />
              <el-option label="年数总和法" value="SYD" />
            </el-select>
          </el-form-item>
          <el-form-item label="生效期间">
            <el-input v-model="policyForm.effectivePeriod" placeholder="yyyyMM，默认系统下期" />
          </el-form-item>
          <el-form-item label="原因" required>
            <el-input v-model="policyForm.reason" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="policyVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="doChangePolicy">提交（单签）</el-button>
        </template>
      </el-dialog>

      <!-- 减值对话框 -->
      <el-dialog v-model="impairVisible" title="资产减值（BR-4.13-15：次月按减值后 NBV 重算）" width="440px">
        <el-form label-width="90px" size="small">
          <el-form-item label="卡片ID" required>
            <el-input v-model="impairForm.cardId" placeholder="资产卡片 ID" />
          </el-form-item>
          <el-form-item label="减值金额" required>
            <el-input-number v-model="impairForm.amount" :min="0.01" :precision="2" :step="1000" style="width:100%;" />
          </el-form-item>
          <el-form-item label="原因" required>
            <el-input v-model="impairForm.reason" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="impairVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="doImpair">提交（单签）</el-button>
        </template>
      </el-dialog>
    </template>

    <!-- ================= 7.2.3 凭证生成 ================= -->
    <template v-else>
      <el-card shadow="never">
        <div class="toolbar">
          <el-input v-model="vSearch" placeholder="来源单号（run 单号，如 JZ202609-0001）" style="width:260px;" clearable />
          <el-button type="primary" @click="loadVouchers">反查凭证</el-button>
        </div>

        <el-table :data="voucherRows" size="small" border v-loading="loading">
          <el-table-column prop="voucherNo" label="凭证号" width="150">
            <template #default="{ row }"><b>{{ row.voucherNo }}</b></template>
          </el-table-column>
          <el-table-column prop="voucherType" label="类型" width="80" align="center" />
          <el-table-column prop="period" label="会计月" width="90" align="center" />
          <el-table-column label="借方合计" width="120" align="right">
            <template #default="{ row }">¥ {{ fmt(row.totalDr) }}</template>
          </el-table-column>
          <el-table-column label="贷方合计" width="120" align="right">
            <template #default="{ row }">¥ {{ fmt(row.totalCr) }}</template>
          </el-table-column>
          <el-table-column prop="sourceDocNo" label="来源单号" width="150" />
          <el-table-column prop="status" label="状态" width="90" align="center" />
          <el-table-column label="操作" width="110" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openVoucherDetails(row)">凭证明细</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" style="margin-top:12px;" v-if="voucherDetail.rows">
        <template #header><span>凭证明细 —— {{ voucherDetail.voucherNo }}</span></template>
        <el-table :data="voucherDetail.rows" size="small" border>
          <el-table-column prop="lineNo" label="行号" width="70" align="center" />
          <el-table-column prop="accountCode" label="科目" width="110" align="center" />
          <el-table-column label="方向" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.direction === 'DR' ? 'danger' : 'success'">
                {{ row.direction === 'DR' ? '借' : '贷' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="130" align="right">
            <template #default="{ row }">¥ {{ fmt(row.amount) }}</template>
          </el-table-column>
          <el-table-column prop="summary" label="摘要" min-width="160" />
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  runDepreciationApi, getPreviewApi, getRunPageApi, getRunDetailApi, reverseRunApi,
  getChangePageApi, changePolicyApi, impairmentApi, approveChangeApi, rejectChangeApi,
  getVoucherApi, getDetailsByVoucherApi
} from '@/api/eam/depreciation'

const route = useRoute()
const userStore = useUserStore()
const tab = route.meta.tab || 'run'

const loading = ref(false)
const running = ref(false)
const saving = ref(false)

// ---- 7.2.1 ----
const periodTag = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const preview = reactive({ cards: [], totalDepreciation: 0, missingParams: [], alreadyRun: false })
const runRows = ref([])
const detail = reactive({ run: null, details: [] })

// ---- 7.2.2 ----
const changeType = ref('')
const changeRows = ref([])
const policyVisible = ref(false)
const impairVisible = ref(false)
const policyForm = reactive({ cardId: '', afterLifeYears: null, afterResidualRate: 0.05,
  afterMethod: 'STRAIGHT_LINE', reason: '', effectivePeriod: '' })
const impairForm = reactive({ cardId: '', amount: null, reason: '' })

// ---- 7.2.3 ----
const vSearch = ref('')
const voucherRows = ref([])
const voucherDetail = reactive({ voucherNo: '', rows: null })

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canRun = computed(() => myRoles.value.some(r => ['ROLE_ADMIN', 'ROLE_FIN_ASSET'].includes(r)))
const canChange = computed(() => myRoles.value.some(r => ['ROLE_ADMIN', 'ROLE_ASSET_ADMIN'].includes(r)))
const canApprove = computed(() => myRoles.value.some(r => ['ROLE_ADMIN', 'ROLE_FIN_ASSET'].includes(r)))

function fmt(v) { return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }
function pct(v) { return `${(Number(v || 0) * 100).toFixed(2)}%` }
function methodLabel(m) {
  return { STRAIGHT_LINE: '直线法', DOUBLE_DECLINING: '双倍余额递减', SYD: '年数总和法' }[m] || m
}

async function loadPreview() {
  loading.value = true
  try {
    const res = await getPreviewApi(periodTag.value)
    Object.assign(preview, res.data || {})
  } finally { loading.value = false }
}

async function loadRuns() {
  const res = await getRunPageApi({ current: 1, size: 50, periodTag: periodTag.value })
  runRows.value = (res.data && res.data.records) || []
}

async function doRun() {
  running.value = true
  try {
    const res = await runDepreciationApi(periodTag.value)
    ElMessage.success(`计提完成：${res.data.cardCount} 张卡，折旧 ¥ ${fmt(res.data.totalDepreciation)}`
      + (res.data.policyApplied ? `；应用政策变更 ${res.data.policyApplied} 条` : ''))
    await Promise.all([loadPreview(), loadRuns()])
  } catch (e) { /* 422/409 由 request 层弹出 */ }
  finally { running.value = false }
}

async function openRunDetail(row) {
  const res = await getRunDetailApi(row.id)
  Object.assign(detail, res.data || {})
}

async function doReverse(row) {
  try {
    await ElMessageBox.confirm(`确认冲回计提单 ${row.runNo}？将红冲凭证并回滚台账，可重新计提。`,
      '整单冲回', { type: 'warning' })
  } catch { return }
  await reverseRunApi(row.id, '前端冲回')
  ElMessage.success('已冲回，期间已放行可重提')
  await Promise.all([loadPreview(), loadRuns()])
}

async function loadChanges() {
  loading.value = true
  try {
    const res = await getChangePageApi({ current: 1, size: 50, changeType: changeType.value })
    changeRows.value = (res.data && res.data.records) || []
  } finally { loading.value = false }
}

async function doChangePolicy() {
  saving.value = true
  try {
    await changePolicyApi({ ...policyForm })
    ElMessage.success('已提交（财务资产会计单签）')
    policyVisible.value = false
    await loadChanges()
  } finally { saving.value = false }
}

async function doImpair() {
  saving.value = true
  try {
    await impairmentApi({ ...impairForm })
    ElMessage.success('已提交（财务资产会计单签）')
    impairVisible.value = false
    await loadChanges()
  } finally { saving.value = false }
}

async function doApprove(row) {
  await approveChangeApi(row.id)
  ElMessage.success('已通过')
  await loadChanges()
}

async function doReject(row) {
  let reason = '驳回'
  try {
    const r = await ElMessageBox.prompt('驳回原因', '驳回', { inputValue: reason })
    reason = r.value
  } catch { return }
  await rejectChangeApi(row.id, reason)
  ElMessage.success('已驳回')
  await loadChanges()
}

async function loadVouchers() {
  loading.value = true
  try {
    const res = await getVoucherApi(vSearch.value || undefined)
    voucherRows.value = res.data || []
  } finally { loading.value = false }
}

async function openVoucherDetails(row) {
  const res = await getDetailsByVoucherApi(row.id)
  voucherDetail.voucherNo = row.voucherNo
  voucherDetail.rows = res.data || []
}

onMounted(async () => {
  if (tab === 'run') { await loadPreview(); await loadRuns() }
  else if (tab === 'change') await loadChanges()
})
</script>

<style scoped>
.eam-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.spacer { flex: 1; }
</style>
