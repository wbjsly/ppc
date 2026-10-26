<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      :title="tab === 'workbench'
        ? '年度盘点（7.5.1）：计划 → 清单下发（基准日快照冻结 BR-29）→ 实盘录入/导入 → 覆盖率 → 结案卡控（BR-26 L1）→ 报告归档'
        : '差异处理（7.5.2）：自动比对四类差异 → 调查留痕 → 逐单分级审批 → 盘盈建卡（BR-27）/盘亏责任认定与核销（BR-28）→ 标签异常冻结（BR-30）'"
      :description="tab === 'workbench'
        ? '抽样按分类风险等级比例（高 50%/中 30%/低 10%），抽样范围覆盖全部资产类别；基准日后变动单独列示不计覆盖率。'
        : '盘亏审批通过即挂待处理财产损溢，责任认定完成后方可核销；标签异常冻结调拨与处置直至补标解冻。'" />

    <!-- ================= 7.5.1 盘点工作台 ================= -->
    <template v-if="tab === 'workbench'">
      <el-card shadow="never">
        <div class="toolbar">
          <el-button type="primary" @click="openPlanDialog()">新建盘点计划</el-button>
          <el-button @click="loadSamplePreview">抽样预览</el-button>
          <span class="spacer" />
          <el-button plain size="small" @click="loadPlans">刷新</el-button>
        </div>

        <el-table :data="planRows" size="small" border v-loading="loading" max-height="300">
          <el-table-column prop="cntNo" label="盘点单号" width="170" />
          <el-table-column label="范围" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.countType === 'FULL' ? 'primary' : 'warning'">
                {{ row.countType === 'FULL' ? '全面' : '抽样' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="baseDate" label="基准日" width="110" align="center" />
          <el-table-column prop="method" label="方式" width="80" align="center">
            <template #default="{ row }">{{ row.method === 'SCAN' ? '扫描' : '手工' }}</template>
          </el-table-column>
          <el-table-column prop="totalQty" label="应盘" width="70" align="right" />
          <el-table-column prop="countedQty" label="已盘" width="70" align="right" />
          <el-table-column label="覆盖率" width="90" align="right">
            <template #default="{ row }">
              {{ row.coverageRate != null ? (Number(row.coverageRate) * 100).toFixed(2) + '%' : '—' }}
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="planTag(row.status)" size="small">{{ planLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="330" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small"
                @click="doIssue(row)">下发清单</el-button>
              <template v-if="row.status === 'ISSUED' || row.status === 'COUNTING'">
                <el-button link type="success" size="small" @click="openEntry(row)">实盘录入</el-button>
                <el-button link type="primary" size="small" @click="doExport(row)">导出</el-button>
                <el-button link type="warning" size="small" @click="doImport(row)">导入</el-button>
                <el-button link type="info" size="small" @click="viewLines(row)">清单</el-button>
              </template>
              <el-button v-if="row.status === 'COUNTING' || row.status === 'DIFF_REVIEW'" link
                type="danger" size="small" @click="doCompare(row)">自动比对</el-button>
              <el-button v-if="row.status === 'DIFF_REVIEW' || row.status === 'ADJUSTING'" link
                type="danger" size="small" @click="doSubmitReport(row)">结案报告</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 覆盖率与基准日后变动 -->
      <el-card shadow="never" style="margin-top:12px;" v-if="coverage">
        <template #header>覆盖率（结案卡控 BR-26）</template>
        <el-descriptions :column="4" size="small" border>
          <el-descriptions-item label="应盘">{{ coverage.totalQty }}</el-descriptions-item>
          <el-descriptions-item label="已盘">{{ coverage.countedQty }}</el-descriptions-item>
          <el-descriptions-item label="覆盖率">
            {{ (Number(coverage.coverageRate) * 100).toFixed(2) }}%
          </el-descriptions-item>
          <el-descriptions-item label="未盘样例">
            {{ (coverage.unCountedSample || []).join('、') || '无' }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 新建计划对话框 -->
      <el-dialog v-model="planDlg" title="新建盘点计划" width="560px">
        <el-form :model="planForm" label-width="110px">
          <el-form-item label="盘点范围">
            <el-radio-group v-model="planForm.countType">
              <el-radio value="FULL">全面盘点</el-radio>
              <el-radio value="SAMPLE">抽样盘点</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="盘点基准日" required>
            <el-date-picker v-model="planForm.baseDate" type="date" value-format="YYYY-MM-DD" />
            <span class="hint">清单下发即按此日快照冻结（BR-29）</span>
          </el-form-item>
          <el-form-item label="盘点方式">
            <el-radio-group v-model="planForm.method">
              <el-radio value="SCAN">标签扫描</el-radio>
              <el-radio value="MANUAL">手工对账</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="盘点说明"><el-input v-model="planForm.planDesc" type="textarea" /></el-form-item>
          <el-form-item label="盘点人员">
            <div style="width:100%">
              <div v-for="(m, i) in planForm.members" :key="i" style="display:flex;gap:8px;margin-bottom:6px;">
                <el-input v-model="m.memberName" placeholder="姓名" style="width:140px;" />
                <el-input v-model="m.groupName" placeholder="分组（两人一组）" style="width:160px;" />
                <el-button link type="danger" @click="planForm.members.splice(i, 1)">删</el-button>
              </div>
              <el-button link type="primary" @click="planForm.members.push({ memberName: '', groupName: '' })">+ 添加人员</el-button>
            </div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="planDlg = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="doCreatePlan">创建（含分组）</el-button>
        </template>
      </el-dialog>

      <!-- 实盘录入对话框 -->
      <el-dialog v-model="entryDlg" :title="`实盘录入（${entryCntNo}）`" width="520px">
        <el-form label-width="110px">
          <el-form-item label="扫描卡号">
            <el-input v-model="entryForm.cardNo" placeholder="ZC 开头卡号，回车录入" autofocus />
          </el-form-item>
          <el-form-item label="实盘状态" required>
            <el-select v-model="entryForm.actualStatus" style="width:220px;">
              <el-option value="IN_USE" label="在用" />
              <el-option value="IDLE" label="闲置" />
              <el-option value="DAMAGED" label="损坏" />
              <el-option value="PENDING_SCRAP" label="待报废" />
              <el-option value="MISSING" label="盘亏（账有实物无）" />
              <el-option value="UNKNOWN" label="无标签待确认" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="entryForm.actualNote" /></el-form-item>
      <el-form-item v-if="entryForm.actualStatus === 'SURPLUS'" label="分类码">
        <el-input v-model="entryForm.categoryCode" placeholder="盘盈必填（如 MACH），决定自动带价分类" />
      </el-form-item>
          <el-form-item label="刷新闲置锚">
            <el-switch v-model="entryForm.refreshLastUsed" />
            <span class="hint">在用确认时更新 LAST_USED_AT（共享池数据源）</span>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="entryDlg = false">关闭</el-button>
          <el-button type="primary" :loading="saving" @click="doEntry">录入</el-button>
        </template>
      </el-dialog>

      <!-- 清单查看对话框 -->
      <el-dialog v-model="linesDlg" :title="`盘点清单（${linesCntNo}）`" width="900px">
        <el-table :data="lineRows" size="small" border max-height="420">
          <el-table-column prop="cardNo" label="卡号" width="140" />
          <el-table-column prop="snapName" label="快照名称" min-width="140" />
          <el-table-column prop="snapDept" label="快照部门" min-width="100" />
          <el-table-column prop="snapCc" label="成本中心" width="100" />
          <el-table-column prop="snapNbv" label="快照NBV" width="100" align="right" />
          <el-table-column prop="actualStatus" label="实盘状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.actualStatus" size="small">{{ actualLabel(row.actualStatus) }}</el-tag>
              <span v-else class="hint">未盘</span>
            </template>
          </el-table-column>
          <el-table-column prop="countedBy" label="盘点人" width="90" />
        </el-table>
      </el-dialog>

      <!-- 抽样预览对话框 -->
      <el-dialog v-model="previewDlg" title="抽样预览（不落库）" width="720px">
        <el-table :data="previewRows" size="small" border max-height="380">
          <el-table-column prop="categoryCode" label="分类" width="100" />
          <el-table-column prop="categoryName" label="分类名称" min-width="120" />
          <el-table-column prop="riskLevel" label="风险" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.riskLevel === 'HIGH' ? 'danger' : row.riskLevel === 'LOW' ? 'info' : 'warning'">
                {{ { HIGH: '高', MID: '中', LOW: '低' }[row.riskLevel] || row.riskLevel }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sampleRate" label="比例%" width="80" align="right" />
          <el-table-column prop="cardCount" label="在册卡数" width="90" align="right" />
          <el-table-column prop="sampleCount" label="预计抽样" width="90" align="right" />
        </el-table>
      </el-dialog>
    </template>

    <!-- ================= 7.5.2 差异处理 ================= -->
    <template v-else>
      <el-card shadow="never">
        <div class="toolbar">
          <el-select v-model="diffFilter.diffType" placeholder="差异类型" clearable style="width:150px;">
            <el-option value="OVER" label="盘盈" />
            <el-option value="SHORT" label="盘亏" />
            <el-option value="TAG_ISSUE" label="标签异常" />
            <el-option value="STATUS_CONFLICT" label="账实冲突" />
            <el-option value="PENDING_CONFIRM" label="待确认" />
          </el-select>
          <el-select v-model="diffFilter.status" placeholder="状态" clearable style="width:150px;">
            <el-option value="PENDING" label="待审批" />
            <el-option value="PENDING_APPROVE" label="审批中" />
            <el-option value="REJECTED" label="已驳回" />
            <el-option value="LIABILITY_PENDING" label="待责任认定" />
            <el-option value="READY_TO_POST" label="待账务" />
            <el-option value="POSTED" label="已入账" />
            <el-option value="CLOSED" label="已闭环" />
          </el-select>
          <el-button @click="loadDiffs">查询</el-button>
          <el-button @click="loadFrozen">冻结管理</el-button>
          <span class="spacer" />
          <el-button plain size="small" @click="loadDiffs">刷新</el-button>
        </div>

        <el-table :data="diffRows" size="small" border v-loading="loading" max-height="380">
          <el-table-column prop="cntNo" label="盘点单" width="160" />
          <el-table-column label="类型" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="diffTag(row.diffType)">{{ diffLabel(row.diffType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="cardNo" label="卡号" width="140" />
          <el-table-column prop="snapName" label="资产名称" min-width="130" />
          <el-table-column prop="diffAmount" label="差异金额" width="100" align="right" />
          <el-table-column prop="suggestedPrice" label="建议价" width="90" align="right" />
          <el-table-column prop="status" label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="diffStatusTag(row.status)" size="small">{{ diffStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="330" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING' || row.status === 'REJECTED'" link
                type="warning" size="small" @click="doInvestigate(row)">调查</el-button>
              <el-button v-if="row.status === 'PENDING' || row.status === 'REJECTED'" link
                type="primary" size="small" @click="doSubmitApproval(row)">提交审批</el-button>
              <el-button v-if="row.status === 'LIABILITY_PENDING'" link type="warning" size="small"
                @click="openLiability(row)">责任认定</el-button>
              <el-button v-if="row.status === 'READY_TO_POST'" link type="danger" size="small"
                @click="doPostAdjust(row)">账务执行</el-button>
              <el-button v-if="row.diffType === 'TAG_ISSUE' && row.status !== 'CLOSED'" link
                type="info" size="small" @click="doCloseTag(row)">补标解冻</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 责任认定对话框 -->
      <el-dialog v-model="liabilityDlg" title="盘亏责任认定（BR-28）" width="520px">
        <el-form label-width="110px">
          <el-form-item label="认定结论" required>
            <el-radio-group v-model="liabilityForm.liabilityType">
              <el-radio value="NONE">无人为责任</el-radio>
              <el-radio value="COMPENSATE">责任人赔偿</el-radio>
              <el-radio value="INSURANCE">保险理赔</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="责任人">
            <el-input v-model="liabilityForm.liabilityPerson" />
          </el-form-item>
          <el-form-item v-if="liabilityForm.liabilityType !== 'NONE'" label="赔偿/理赔金额" required>
            <el-input-number v-model="liabilityForm.compensateAmount" :min="0" :precision="2" />
          </el-form-item>
          <el-form-item label="认定依据" required>
            <el-input v-model="liabilityForm.basis" type="textarea"
              placeholder="责任认定书/报案记录等（C-4.13-04 L5 留痕）" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="liabilityDlg = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="doLiability">提交认定</el-button>
        </template>
      </el-dialog>

      <!-- 冻结管理对话框 -->
      <el-dialog v-model="frozenDlg" title="盘点冻结管理（BR-30：冻结调拨与处置）" width="760px">
        <el-table :data="frozenRows" size="small" border max-height="380">
          <el-table-column prop="cardNo" label="卡号" width="140" />
          <el-table-column prop="assetName" label="资产名称" min-width="150" />
          <el-table-column prop="deptName" label="部门" min-width="110" />
          <el-table-column prop="diffType" label="冻结原因" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" type="danger">{{ diffLabel(row.diffType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button v-if="row.diffId" link type="info" size="small"
                @click="doCloseTagByDiff(row)">解冻关闭</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  countPage, countCreate, countTeam, countMaintainTeam, countIssue, countSamplePreview,
  countLinePage, countEntry, countImport, countRefreshCoverage, countSubmitReport,
  diffPage, diffCompare, diffInvestigate, diffSubmitApproval, diffLiability,
  diffPostAdjust, diffFrozenCards, diffCloseTagIssue
} from '@/api/eam/asset-count'
import request from '@/utils/request'

const route = useRoute()
const tab = route.meta.tab || 'workbench'

const loading = ref(false)
const saving = ref(false)

// ================= 盘点工作台 =================
const planRows = ref([])
const coverage = ref(null)
const planDlg = ref(false)
const planForm = ref({})
const entryDlg = ref(false)
const entryForm = ref({})
const entryCntNo = ref('')
const entryPlanId = ref('')
const linesDlg = ref(false)
const lineRows = ref([])
const linesCntNo = ref('')
const previewDlg = ref(false)
const previewRows = ref([])

async function loadPlans() {
  loading.value = true
  try {
    const res = await countPage({ current: 1, size: 100 })
    planRows.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}

function openPlanDialog() {
  planForm.value = {
    countType: 'FULL', baseDate: '', method: 'SCAN', planDesc: '',
    members: [{ memberName: '', groupName: '' }]
  }
  planDlg.value = true
}

async function doCreatePlan() {
  const f = planForm.value
  if (!f.baseDate) { ElMessage.warning('盘点基准日必填'); return }
  saving.value = true
  try {
    const res = await countCreate({
      countType: f.countType, baseDate: f.baseDate, method: f.method, planDesc: f.planDesc
    })
    const id = res.data?.plan?.id
    const members = (f.members || []).filter(m => m.memberName)
    if (members.length) {
      await countMaintainTeam(id, members)
    }
    ElMessage.success('盘点计划已创建（含人员分组）')
    planDlg.value = false
    loadPlans()
  } finally {
    saving.value = false
  }
}

async function doIssue(row) {
  await ElMessageBox.confirm(`确认下发清单？将按基准日 ${row.baseDate} 生成物理快照（BR-29），下发后不可重发。`, '下发清单')
  const res = await countIssue(row.id)
  ElMessage.success(`清单已下发：快照 ${res.data.snapshotRows} 行`)
  loadPlans()
}

async function loadSamplePreview() {
  const res = await countSamplePreview()
  previewRows.value = res.data?.categories || []
  previewDlg.value = true
}

function openEntry(row) {
  entryPlanId.value = row.id
  entryCntNo.value = row.cntNo
  entryForm.value = { cardNo: '', actualStatus: 'IN_USE', actualNote: '', refreshLastUsed: false, categoryCode: '' }
  entryDlg.value = true
}

async function doEntry() {
  const f = entryForm.value
  if (!f.cardNo && !f.actualNote) { ElMessage.warning('卡号或备注必填'); return }
  saving.value = true
  try {
    const res = await countEntry(entryPlanId.value, {
      cardNo: f.cardNo, actualStatus: f.actualStatus,
      actualNote: f.actualNote, refreshLastUsed: f.refreshLastUsed,
      categoryCode: f.categoryCode
    })
    ElMessage.success(`已录入 ${res.data.cardNo || '待确认行'}` +
      (res.data.lastUsedRefreshed ? '（闲置锚已刷新）' : ''))
    entryForm.value = { cardNo: '', actualStatus: 'IN_USE', actualNote: '', refreshLastUsed: f.refreshLastUsed, categoryCode: '' }
    loadPlans()
  } finally {
    saving.value = false
  }
}

async function doImport(row) {
  const { value } = await ElMessageBox.prompt(
    '粘贴 CSV 内容（每行：卡号,实盘状态,备注,刷新闲置锚[0/1]，首行可为表头）：',
    `批量导入实盘（${row.cntNo}）`, {
      inputType: 'textarea', inputValue: 'cardNo,actualStatus,actualNote,refreshLastUsed\n'
    })
  if (!value) return
  const res = await countImport(row.id, value)
  const d = res.data
  ElMessage.success(`导入完成：成功 ${d.success}/${d.total}，失败 ${d.failed}`)
  if (d.errors?.length) {
    console.warn('导入错误清单', d.errors)
  }
  loadPlans()
}

async function doExport(row) {
  const res = await request.get(`/eam/counts/${row.id}/export`, { responseType: 'blob' })
  const url = URL.createObjectURL(res.data || res)
  const a = document.createElement('a')
  a.href = url
  a.download = `${row.cntNo}-盘点清单.csv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('清单已导出')
}

async function viewLines(row) {
  const res = await countLinePage(row.id, { current: 1, size: 200 })
  lineRows.value = res.data?.page?.records || []
  linesCntNo.value = row.cntNo
  linesDlg.value = true
  const cov = await countRefreshCoverage(row.id)
  coverage.value = cov.data
}

async function doCompare(row) {
  const res = await diffCompare(row.id)
  ElMessage.success(`比对完成：新增差异 ${res.data.created} 条（幂等跳过 ${res.data.skipped}）`)
  loadPlans()
}

async function doSubmitReport(row) {
  await ElMessageBox.confirm('确认提交结案报告？覆盖率 <100% 将被阻断（BR-26），全部差异须已闭环。', '结案卡控')
  try {
    const res = await countSubmitReport(row.id)
    ElMessage.success('报告已生成，提交财务资产会计审批归档')
    console.info('报告摘要', res.data.reportSummary)
    loadPlans()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message)
  }
}

// ================= 差异处理 =================
const diffRows = ref([])
const diffFilter = ref({ diffType: '', status: '' })
const liabilityDlg = ref(false)
const liabilityForm = ref({})
const liabilityId = ref('')
const frozenDlg = ref(false)
const frozenRows = ref([])

async function loadDiffs() {
  loading.value = true
  try {
    const res = await diffPage({
      current: 1, size: 200,
      diffType: diffFilter.value.diffType || undefined,
      status: diffFilter.value.status || undefined
    })
    diffRows.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}

async function doInvestigate(row) {
  const { value } = await ElMessageBox.prompt(
    '差异原因（位置错误/标签错误/未及时转资/已报废未销账/查无原因）：',
    '差异调查留痕（C-4.13-04）', {
      inputValue: row.reason || '',
      inputPattern: /\S+/, inputErrorMessage: '原因必填'
    })
  await diffInvestigate(row.id, { reason: value, evidence: '' })
  ElMessage.success('调查已留痕')
  loadDiffs()
}

async function doSubmitApproval(row) {
  const res = await diffSubmitApproval(row.id)
  ElMessage.success(`已提交审批（${res.data.nodes} 节点，阈值 ${res.data.threshold}）`)
  loadDiffs()
}

function openLiability(row) {
  liabilityId.value = row.id
  liabilityForm.value = { liabilityType: 'NONE', liabilityPerson: '', compensateAmount: 0, basis: '' }
  liabilityDlg.value = true
}

async function doLiability() {
  const f = liabilityForm.value
  if (!f.basis) { ElMessage.warning('认定依据必填'); return }
  saving.value = true
  try {
    await diffLiability(liabilityId.value, f)
    ElMessage.success('责任认定完成，可执行账务核销（BR-28）')
    liabilityDlg.value = false
    loadDiffs()
  } finally {
    saving.value = false
  }
}

async function doPostAdjust(row) {
  const tip = row.diffType === 'OVER'
    ? '按重置成本建卡并生成盘盈凭证（BR-27）'
    : '按 NBV 核销（赔偿冲减损失），卡片置已处置（BR-28）'
  await ElMessageBox.confirm(`确认账务执行？${tip}`, '账务执行')
  const res = await diffPostAdjust(row.id)
  ElMessage.success(res.data.newCardNo
    ? `盘盈入账完成：新卡 ${res.data.newCardNo}`
    : `核销完成：损失 ${res.data.loss}，赔偿 ${res.data.compensate}`)
  loadDiffs()
}

async function doCloseTag(row) {
  await ElMessageBox.confirm('确认补打/更换标签完成，解冻并关闭该差异单？', '补标解冻（BR-30）')
  await diffCloseTagIssue(row.id)
  ElMessage.success('已解冻并关闭')
  loadDiffs()
}

async function loadFrozen() {
  const res = await diffFrozenCards()
  frozenRows.value = res.data || []
  frozenDlg.value = true
}

async function doCloseTagByDiff(row) {
  await diffCloseTagIssue(row.diffId)
  ElMessage.success(`已解冻 ${row.cardNo}`)
  const res = await diffFrozenCards()
  frozenRows.value = res.data || []
}

// ================= 标签映射 =================
const planLabel = (s) => ({ DRAFT: '草稿', ISSUED: '已下发', COUNTING: '盘点中', DIFF_REVIEW: '差异复核', ADJUSTING: '账务调整', CLOSED: '已结案' }[s] || s)
const planTag = (s) => ({ DRAFT: 'info', ISSUED: 'primary', COUNTING: 'warning', DIFF_REVIEW: 'warning', ADJUSTING: 'warning', CLOSED: 'success' }[s] || 'info')
const actualLabel = (s) => ({ IN_USE: '在用', IDLE: '闲置', DAMAGED: '损坏', PENDING_SCRAP: '待报废', MISSING: '盘亏', SURPLUS: '盘盈', UNKNOWN: '待确认' }[s] || s)
const diffLabel = (s) => ({ OVER: '盘盈', SHORT: '盘亏', TAG_ISSUE: '标签异常', STATUS_CONFLICT: '账实冲突', PENDING_CONFIRM: '待确认' }[s] || s)
const diffTag = (s) => ({ OVER: 'success', SHORT: 'danger', TAG_ISSUE: 'warning', STATUS_CONFLICT: 'danger', PENDING_CONFIRM: 'info' }[s] || 'info')
const diffStatusLabel = (s) => ({ PENDING: '待审批', PENDING_APPROVE: '审批中', REJECTED: '已驳回', LIABILITY_PENDING: '待责任认定', READY_TO_POST: '待账务', POSTED: '已入账', CLOSED: '已闭环' }[s] || s)
const diffStatusTag = (s) => ({ PENDING: 'info', PENDING_APPROVE: 'warning', REJECTED: 'danger', LIABILITY_PENDING: 'warning', READY_TO_POST: 'warning', POSTED: 'primary', CLOSED: 'success' }[s] || 'info')

onMounted(() => {
  if (tab === 'workbench') {
    loadPlans()
  } else {
    loadDiffs()
  }
})
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.spacer { flex: 1; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
</style>
