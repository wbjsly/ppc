<template>
  <div class="cost-collect">
    <el-card style="margin-bottom: 12px;">
      <div class="toolbar">
        <el-select v-model="projectId" placeholder="选择项目" filterable clearable style="width: 320px;">
          <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="p.name" />
        </el-select>
        <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
        <el-tag v-if="frozen" type="danger" effect="dark">项目已冻结——新增支出动作已被拦截</el-tag>
      </div>
      <div class="hint">
        五类成本（人工/材料/外协/差旅/间接）按 WBS 归集（BR-4.8-20）；归集过账自动生成会计凭证（SOURCE_TYPE=PROJECT_COST）。
      </div>
    </el-card>

    <el-tabs v-model="tab" type="border-card">
      <!-- Tab1 归集台账 -->
      <el-tab-pane label="归集台账" name="ledger">
        <el-table :data="ledger" v-loading="loading" stripe>
          <el-table-column prop="collectDate" label="归集日" width="110" />
          <el-table-column label="类别" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="typeTag(row.costType)">{{ typeName(row.costType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="120" :precision="2" />
          <el-table-column prop="wbsId" label="WBS 任务" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.wbsId || '（项目级）' }}</template>
          </el-table-column>
          <el-table-column label="来源" min-width="200">
            <template #default="{ row }">{{ sourceName(row.sourceType) }} / {{ row.sourceId }}</template>
          </el-table-column>
          <el-table-column prop="voucherId" label="凭证" width="140" show-overflow-tooltip />
        </el-table>
        <div style="margin-top: 12px;" v-if="summary">
          <el-descriptions title="五分类合计" :column="6" border size="small">
            <el-descriptions-item label="人工">{{ summary.LABOR }}</el-descriptions-item>
            <el-descriptions-item label="材料">{{ summary.MATERIAL }}</el-descriptions-item>
            <el-descriptions-item label="外协">{{ summary.SUBCONTRACT }}</el-descriptions-item>
            <el-descriptions-item label="差旅">{{ summary.TRAVEL }}</el-descriptions-item>
            <el-descriptions-item label="间接">{{ summary.INDIRECT }}</el-descriptions-item>
            <el-descriptions-item label="合计"><b>{{ summary.total }}</b></el-descriptions-item>
          </el-descriptions>
        </div>
      </el-tab-pane>

      <!-- Tab2 领料单 -->
      <el-tab-pane label="领料单" name="material">
        <div class="toolbar" style="margin-bottom: 10px;">
          <el-button type="primary" :disabled="!projectId" @click="openMat()">新建领料单</el-button>
        </div>
        <el-table :data="matIssues" v-loading="loading" stripe>
          <el-table-column prop="issue.issueNo" label="单号" width="160" />
          <el-table-column prop="issue.issueDate" label="领用日" width="110" />
          <el-table-column prop="lineCount" label="行数" width="70" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.issue.status)">{{ stName(row.issue.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="issue.remark" label="备注" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.issue.status === 'SUBMITTED'" link type="success"
                @click="reviewIssue(row.issue.id, true)">通过</el-button>
              <el-button v-if="row.issue.status === 'SUBMITTED'" link type="danger"
                @click="reviewIssue(row.issue.id, false)">驳回</el-button>
              <el-button v-if="row.issue.status === 'DRAFT' || row.issue.status === 'REJECTED'"
                link type="primary" @click="openMat(row.issue.id)">编辑并提交</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 领料单编辑对话框 -->
        <el-dialog v-model="matVisible" title="项目领料单" width="880px" :close-on-click-modal="false">
          <el-form label-width="90px" size="small">
            <el-form-item label="领用日期" required>
              <el-date-picker v-model="matForm.issueDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="matForm.remark" maxlength="200" />
            </el-form-item>
          </el-form>
          <el-table :data="matForm.lines" size="small" border>
            <el-table-column label="WBS 任务*" width="200">
              <template #default="{ row }">
                <el-select v-model="row.wbsId" filterable placeholder="任务级节点">
                  <el-option v-for="t in taskOptions" :key="t.id" :value="t.id" :label="t.wbsCode + ' ' + t.name" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="物料编码*" width="140">
              <template #default="{ row }"><el-input v-model="row.itemId" /></template>
            </el-table-column>
            <el-table-column label="物料名称" width="140">
              <template #default="{ row }"><el-input v-model="row.itemName" /></template>
            </el-table-column>
            <el-table-column label="仓库*" width="130">
              <template #default="{ row }"><el-input v-model="row.warehouseCode" /></template>
            </el-table-column>
            <el-table-column label="数量*" width="110">
              <template #default="{ row }"><el-input-number v-model="row.qty" :min="0.001" :precision="3" controls-position="right" style="width: 100%;" /></template>
            </el-table-column>
            <el-table-column label="单价*" width="120">
              <template #default="{ row }"><el-input-number v-model="row.unitPrice" :min="0" :precision="4" controls-position="right" style="width: 100%;" /></template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ $index }">
                <el-button link type="danger" @click="matForm.lines.splice($index, 1)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button size="small" style="margin-top: 8px;" @click="matForm.lines.push({ wbsId: '', itemId: '', itemName: '', warehouseCode: '', qty: 1, unitPrice: 0 })">+ 加行</el-button>
          <template #footer>
            <el-button @click="matVisible = false">取消</el-button>
            <el-button @click="saveMat(false)">存草稿</el-button>
            <el-button type="primary" @click="saveMat(true)">提交（扣库存）</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- Tab3 外协合同与付款 -->
      <el-tab-pane label="外协合同/付款" name="subcontract">
        <div class="toolbar" style="margin-bottom: 10px;">
          <el-button type="primary" :disabled="!projectId" @click="scVisible = true">新建合同</el-button>
          <el-button :disabled="!projectId" @click="openPay()">新建付款单</el-button>
        </div>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-table :data="contracts" v-loading="loading" size="small" stripe height="320">
              <el-table-column prop="contract.contractNo" label="合同号" width="150" />
              <el-table-column prop="contract.supplierName" label="供应商" min-width="110" show-overflow-tooltip />
              <el-table-column prop="contract.totalAmount" label="总额" width="90" :precision="2" />
              <el-table-column label="剩余" width="90">
                <template #default="{ row }"><span :style="{ color: row.remain <= 0 ? '#f56c6c' : '' }">{{ row.remain }}</span></template>
              </el-table-column>
              <el-table-column label="" width="70">
                <template #default="{ row }">
                  <el-tag v-if="row.contract.noWbsFlag === '1'" type="warning" size="small">无WBS</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-col>
          <el-col :span="12">
            <el-table :data="payments" v-loading="loading" size="small" stripe height="320">
              <el-table-column prop="payment.payNo" label="付款单号" width="150" />
              <el-table-column prop="payment.amount" label="金额" width="90" :precision="2" />
              <el-table-column prop="payment.payDate" label="付款日" width="100" />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="stTag(row.payment.status)">{{ stName(row.payment.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="row.payment.status === 'SUBMITTED'" link type="success"
                    @click="reviewPay(row.payment.id, true)">通过</el-button>
                  <el-button v-if="row.payment.status === 'SUBMITTED'" link type="danger"
                    @click="reviewPay(row.payment.id, false)">驳回</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-col>
        </el-row>

        <el-dialog v-model="scVisible" title="外协合同台账" width="520px">
          <el-form label-width="100px" size="small">
            <el-form-item label="供应商"><el-input v-model="scForm.supplierName" /></el-form-item>
            <el-form-item label="含税总额" required>
              <el-input-number v-model="scForm.totalAmount" :min="0.01" :precision="2" style="width: 200px;" />
            </el-form-item>
            <el-form-item label="WBS 任务">
              <el-select v-model="scForm.wbsId" filterable clearable placeholder="可空（空=项目级，L4 提示）">
                <el-option v-for="t in taskOptions" :key="t.id" :value="t.id" :label="t.wbsCode + ' ' + t.name" />
              </el-select>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="scVisible = false">取消</el-button>
            <el-button type="primary" @click="saveSc">保存</el-button>
          </template>
        </el-dialog>

        <el-dialog v-model="payVisible" title="外协付款单" width="520px">
          <el-form label-width="90px" size="small">
            <el-form-item label="合同" required>
              <el-select v-model="payForm.contractId" filterable placeholder="选择合同（校验剩余余额）">
                <el-option v-for="c in contracts" :key="c.contract.id"
                  :value="c.contract.id"
                  :label="c.contract.contractNo + '（剩余 ' + c.remain + '）'" />
              </el-select>
            </el-form-item>
            <el-form-item label="金额" required>
              <el-input-number v-model="payForm.amount" :min="0.01" :precision="2" style="width: 200px;" />
            </el-form-item>
            <el-form-item label="付款日期" required>
              <el-date-picker v-model="payForm.payDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="payVisible = false">取消</el-button>
            <el-button type="primary" @click="savePay">提交</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- Tab4 差旅报销 -->
      <el-tab-pane label="差旅报销" name="travel">
        <div class="toolbar" style="margin-bottom: 10px;">
          <el-button type="primary" @click="openClaim()">新建报销单</el-button>
        </div>
        <el-table :data="claims" v-loading="loading" stripe>
          <el-table-column prop="claim.claimNo" label="单号" width="160" />
          <el-table-column prop="claim.empName" label="员工" width="100" />
          <el-table-column label="项目" min-width="150">
            <template #default="{ row }">
              <el-tag v-if="row.claim.deptOnly === '1'" type="info" size="small">部门费用</el-tag>
              <span v-else>{{ row.claim.projectId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="期间" width="180">
            <template #default="{ row }">{{ row.claim.startDate }} ~ {{ row.claim.endDate }}</template>
          </el-table-column>
          <el-table-column prop="claim.amount" label="金额" width="90" :precision="2" />
          <el-table-column label="超标" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.claim.overStandardFlag === '1'" type="warning" size="small">超标</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.claim.status)">{{ stName(row.claim.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.claim.status === 'SUBMITTED' && row.claim.overStandardFlag !== '1'"
                link type="success" @click="reviewClaim(row.claim.id, true)">通过</el-button>
              <el-button v-if="row.claim.status === 'SUBMITTED' && row.claim.overStandardFlag !== '1'"
                link type="danger" @click="reviewClaim(row.claim.id, false)">驳回</el-button>
              <el-tooltip v-if="row.claim.overStandardFlag === '1' && row.claim.status === 'SUBMITTED'"
                content="超标单据走 L2 财务审批（审批中心待办）" placement="top">
                <el-tag type="warning" size="small">L2 审批中</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>

        <el-dialog v-model="claimVisible" title="差旅报销单" width="600px">
          <el-form label-width="110px" size="small">
            <el-form-item label="项目">
              <el-select v-model="claimForm.projectId" filterable clearable placeholder="可空（空=归部门费用）">
                <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="p.name" />
              </el-select>
            </el-form-item>
            <el-form-item label="职级">
              <el-select v-model="claimForm.grade" style="width: 200px;">
                <el-option value="JUNIOR" label="初级" />
                <el-option value="MID" label="中级" />
                <el-option value="SENIOR" label="高级" />
              </el-select>
            </el-form-item>
            <el-form-item label="出差期间" required>
              <el-date-picker v-model="claimRange" type="daterange" value-format="YYYY-MM-DD"
                start-placeholder="开始" end-placeholder="结束" />
            </el-form-item>
            <el-form-item label="报销金额" required>
              <el-input-number v-model="claimForm.amount" :min="0.01" :precision="2" style="width: 200px;" />
            </el-form-item>
            <el-form-item label="超标原因">
              <el-input v-model="claimForm.overReason" type="textarea" :rows="2"
                placeholder="超出职级日限额标准时必填（提交自动发起 L2 财务审批）" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="claimVisible = false">取消</el-button>
            <el-button type="primary" @click="saveClaim">提交</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- Tab5 间接分摊 -->
      <el-tab-pane label="间接分摊" name="alloc">
        <el-row :gutter="12">
          <el-col :span="10">
            <h4>分摊规则（每项目至多一条生效）</h4>
            <el-table :data="rules" size="small" stripe height="240">
              <el-table-column prop="projectName" label="项目" min-width="140" show-overflow-tooltip />
              <el-table-column label="类型" width="110">
                <template #default="{ row }">{{ row.rule.ruleType === 'HOURS' ? '工时占比' : '预算占比' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.rule.status === '1' ? 'success' : 'info'">
                    {{ row.rule.status === '1' ? '生效' : '停用' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button v-if="row.rule.status === '1'" link type="danger"
                    @click="disableRule(row.rule.id)">停用</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-form inline size="small" style="margin-top: 8px;">
              <el-form-item label="项目">
                <el-select v-model="ruleForm.projectId" filterable style="width: 200px;">
                  <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="p.name" />
                </el-select>
              </el-form-item>
              <el-form-item label="类型">
                <el-select v-model="ruleForm.ruleType" style="width: 130px;">
                  <el-option value="HOURS" label="工时占比" />
                  <el-option value="BUDGET" label="预算占比" />
                </el-select>
              </el-form-item>
              <el-button type="primary" @click="saveRule">保存规则</el-button>
            </el-form>

            <h4>公共费用池</h4>
            <el-form inline size="small">
              <el-form-item label="期间">
                <el-date-picker v-model="poolForm.period" type="month" value-format="YYYY-MM" style="width: 130px;" />
              </el-form-item>
              <el-form-item label="类别">
                <el-select v-model="poolForm.expenseCategory" style="width: 120px;">
                  <el-option value="管理费" /><el-option value="办公费" />
                  <el-option value="IT费用" /><el-option value="其他" />
                </el-select>
              </el-form-item>
              <el-form-item label="金额">
                <el-input-number v-model="poolForm.amount" :min="0.01" :precision="2" style="width: 150px;" />
              </el-form-item>
              <el-button type="primary" @click="savePool">录入</el-button>
            </el-form>
            <el-table :data="pools" size="small" stripe height="160">
              <el-table-column prop="pool.period" label="期间" width="90" />
              <el-table-column prop="pool.expenseCategory" label="类别" width="90" />
              <el-table-column prop="pool.amount" label="金额" width="100" :precision="2" />
            </el-table>
          </el-col>
          <el-col :span="14">
            <div class="toolbar" style="margin-bottom: 8px;">
              <el-date-picker v-model="detailPeriod" type="month" value-format="YYYY-MM" style="width: 130px;" @change="loadDetails" />
              <el-button type="primary" :loading="running" @click="runNow">执行月度分摊</el-button>
            </div>
            <el-table :data="details" size="small" stripe height="460">
              <el-table-column prop="detail.period" label="期间" width="90" />
              <el-table-column prop="projectName" label="项目" min-width="140" show-overflow-tooltip />
              <el-table-column prop="detail.allocRate" label="占比(%)" width="90" :precision="2" />
              <el-table-column prop="detail.amount" label="分摊额" width="100" :precision="2" />
              <el-table-column prop="detail.collectId" label="归集行" min-width="140" show-overflow-tooltip />
            </el-table>
          </el-col>
        </el-row>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as api from '@/api/cost'

const loading = ref(false)
const frozen = ref(false)
const tab = ref('ledger')
const projectId = ref('')
const projects = ref([])
const ledger = ref([])
const summary = ref(null)

const matIssues = ref([])
const contracts = ref([])
const payments = ref([])
const claims = ref([])
const rules = ref([])
const pools = ref([])
const details = ref([])
const taskOptions = ref([])
const detailPeriod = ref(new Date().toISOString().slice(0, 7))
const running = ref(false)

const typeName = t => ({ LABOR: '人工', MATERIAL: '材料', SUBCONTRACT: '外协', TRAVEL: '差旅', INDIRECT: '间接' }[t] || t)
const typeTag = t => ({ LABOR: 'primary', MATERIAL: 'success', SUBCONTRACT: 'warning', TRAVEL: 'info', INDIRECT: 'info' }[t] || '')
const sourceName = s => ({ MAT_ISSUE: '领料单', SUB_PAYMENT: '外协付款', TRAVEL_CLAIM: '差旅报销', HOURS_COLLECT: '工时归集', ALLOC: '间接分摊' }[s] || s)
const stName = s => ({ DRAFT: '草稿', SUBMITTED: '待审', APPROVED: '已通过', REJECTED: '已驳回' }[s] || s)
const stTag = s => ({ DRAFT: 'info', SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger' }[s] || 'info')

async function loadProjects() {
  const { data } = await api.pmProjects()
  projects.value = (data || []).map(p => p.project || p)
}

async function loadAll() {
  if (!projectId.value) return
  loading.value = true
  try {
    const [ledgerRes, sumRes, matRes, scRes, payRes, clRes, ruleRes, poolRes, varRes] = await Promise.all([
      api.collectList({ projectId: projectId.value }),
      api.collectSummary(projectId.value),
      api.matIssueList({ projectId: projectId.value }),
      api.subcontractContracts({ projectId: projectId.value }),
      api.subcontractPayments({ projectId: projectId.value }),
      api.travelClaims({ projectId: projectId.value }),
      api.allocRules(),
      api.costPools({}),
      api.costVariance(projectId.value).catch(() => ({ data: {} }))
    ])
    ledger.value = ledgerRes.data || []
    summary.value = sumRes.data
    matIssues.value = matRes.data || []
    contracts.value = scRes.data || []
    payments.value = payRes.data || []
    claims.value = clRes.data || []
    rules.value = ruleRes.data || []
    pools.value = poolRes.data || []
    frozen.value = !!varRes.data.frozen
    // WBS 任务选项（领料/合同用）
    const { data: wbs } = await api.pmProjects().catch(() => ({ data: [] }))
    void wbs
    const tasks = await loadTasks(projectId.value)
    taskOptions.value = tasks
  } finally {
    loading.value = false
  }
}

async function loadTasks(pid) {
  try {
    const { data } = await request0(`/api/pm/wbs?projectId=${pid}`)
    return (data || []).filter(w => w.level === 3 || w.wbs?.level === 3).map(w => w.wbs || w)
  } catch (e) {
    return []
  }
}

// 轻量直连（wbs api 未在 api/cost 内封装）
import request from '@/utils/request'
function request0(url) { return request({ url, method: 'get' }) }

async function loadDetails() {
  if (!detailPeriod.value) return
  const { data } = await api.allocDetails(detailPeriod.value)
  details.value = data || []
}

// ---- 领料 ----
const matVisible = ref(false)
const matForm = ref({ issueDate: new Date().toISOString().slice(0, 10), remark: '', lines: [] })
async function openMat(id) {
  matForm.value = { issueDate: new Date().toISOString().slice(0, 10), remark: '', lines: [{}] }
  if (id) {
    const { data } = await api.matIssueDetail(id)
    matForm.value = {
      id,
      issueDate: data.issue.issueDate,
      remark: data.issue.remark,
      lines: (data.lines || []).map(l => ({ wbsId: l.wbsId, itemId: l.itemId, itemName: l.itemName, batchNo: l.batchNo, warehouseCode: l.warehouseCode, qty: l.qty, unitPrice: l.unitPrice }))
    }
  }
  matVisible.value = true
}
async function saveMat(submit) {
  if (!projectId.value) { ElMessage.warning('请先选择项目'); return }
  const payload = { ...matForm.value, projectId: projectId.value }
  const fn = submit ? api.submitMatIssue : api.saveMatIssue
  const { data, message } = await fn(payload)
  ElMessage.success(message || (submit ? '已提交并扣减库存' : '已保存草稿'))
  matVisible.value = false
  loadAll()
  void data
}
async function reviewIssue(id, approved) {
  let opinion
  if (!approved) {
    const { value } = await ElMessageBox.prompt('驳回原因', '驳回领料单', { inputPattern: /\S+/, inputErrorMessage: '必填' })
    opinion = value
  }
  await api.reviewMatIssue(id, { approved, opinion })
  ElMessage.success(approved ? '已通过，材料成本已归集并生成凭证' : '已驳回')
  loadAll()
}

// ---- 外协 ----
const scVisible = ref(false)
const scForm = ref({ supplierName: '', totalAmount: 0, wbsId: '' })
async function saveSc() {
  await api.saveSubcontract({ ...scForm.value, projectId: projectId.value })
  ElMessage.success('合同已保存')
  scVisible.value = false
  loadAll()
}
const payVisible = ref(false)
const payForm = ref({ contractId: '', amount: 0, payDate: new Date().toISOString().slice(0, 10) })
function openPay() { payForm.value = { contractId: '', amount: 0, payDate: new Date().toISOString().slice(0, 10) }; payVisible.value = true }
async function savePay() {
  await api.submitSubPayment(payForm.value)
  ElMessage.success('付款单已提交')
  payVisible.value = false
  loadAll()
}
async function reviewPay(id, approved) {
  let opinion
  if (!approved) {
    const { value } = await ElMessageBox.prompt('驳回原因', '驳回付款单', { inputPattern: /\S+/, inputErrorMessage: '必填' })
    opinion = value
  }
  await api.reviewSubPayment(id, { approved, opinion })
  ElMessage.success(approved ? '已通过，外协成本已归集' : '已驳回')
  loadAll()
}

// ---- 差旅 ----
const claimVisible = ref(false)
const claimRange = ref([])
const claimForm = ref({ projectId: '', grade: 'MID', amount: 0, overReason: '' })
function openClaim() {
  claimRange.value = []
  claimForm.value = { projectId: projectId.value, grade: 'MID', amount: 0, overReason: '' }
  claimVisible.value = true
}
async function saveClaim() {
  if (!claimRange.value || claimRange.value.length !== 2) { ElMessage.warning('请选择出差期间'); return }
  await api.submitTravelClaim({
    ...claimForm.value,
    startDate: claimRange.value[0],
    endDate: claimRange.value[1]
  })
  ElMessage.success('已提交（超标单据将走 L2 财务审批）')
  claimVisible.value = false
  loadAll()
}
async function reviewClaim(id, approved) {
  let opinion
  if (!approved) {
    const { value } = await ElMessageBox.prompt('驳回原因', '驳回报销单', { inputPattern: /\S+/, inputErrorMessage: '必填' })
    opinion = value
  }
  await api.reviewTravelClaim(id, { approved, opinion })
  ElMessage.success(approved ? '已通过，差旅成本已归集' : '已驳回')
  loadAll()
}

// ---- 分摊 ----
const ruleForm = ref({ projectId: '', ruleType: 'HOURS' })
const poolForm = ref({ period: new Date().toISOString().slice(0, 7), expenseCategory: '管理费', amount: 0 })
async function saveRule() {
  await api.saveAllocRule(ruleForm.value)
  ElMessage.success('规则已保存')
  loadAll()
}
async function disableRule(id) {
  await api.disableAllocRule(id)
  ElMessage.success('已停用')
  loadAll()
}
async function savePool() {
  await api.saveCostPool(poolForm.value)
  ElMessage.success('费用池已录入')
  loadAll()
}
async function runNow() {
  running.value = true
  try {
    const { data } = await api.runAlloc(detailPeriod.value)
    ElMessage.success(`分摊完成：${data.allocatedDetails} 条明细（跳过 ${data.skippedPools} 个已分摊费用池）`)
    loadDetails()
  } finally {
    running.value = false
  }
}

watch(projectId, () => { if (projectId.value) loadAll() })
onMounted(async () => { await loadProjects() })
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; }
.hint { margin-top: 8px; color: #909399; font-size: 12px; }
h4 { margin: 10px 0 6px; }
</style>
