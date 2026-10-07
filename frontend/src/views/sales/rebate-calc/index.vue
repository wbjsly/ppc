<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.9.1 返利计算（含三配置 Tab）"
              description="达成率 = 季度开票确认额 − 退货退款额 ÷ 季度目标；返利按「超额累进」分段计算（各阶梯仅对落入该阶梯的超额部分计返利，非全额累进）；返利超季度预算余额自动升级销售总监并要求超预算说明与年度平衡方案（C-4.3-05）。数据不完整（存在未完成退货）延期不出结果（FR-4.3-8-1~3）。" />

    <el-tabs v-model="mainTab" class="mb12">
      <!-- ============ 返利计算 ============ -->
      <el-tab-pane label="返利计算" name="calc">
        <el-card shadow="never" class="mb12">
          <template #header>
            <div class="card-head">
              <b>季度返利计算</b>
              <div>
                <el-select v-model="calcForm.customerId" filterable placeholder="选择客户"
                           style="width:260px">
                  <el-option v-for="c in customers" :key="c.id"
                             :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
                </el-select>
                <el-select v-model="calcForm.quarter" style="width:130px;margin-left:8px">
                  <el-option v-for="q in quarters" :key="q" :label="q" :value="q" />
                </el-select>
                <el-button type="primary" size="small" style="margin-left:8px"
                           :loading="calculating" @click="doCalculate">执行计算</el-button>
              </div>
            </div>
          </template>

          <el-alert v-if="calcResult && calcResult.pending" type="warning" :closable="false"
                    show-icon class="mb12"
                    :title="`数据不完整，本季返利延期至数据补齐后计算：${(calcResult.settlement || {}).pendingHint || ''}`" />

          <template v-if="calcResult && calcResult.settlement && !calcResult.pending">
            <el-row :gutter="12" class="mb12">
              <el-col :span="4" v-for="k in kpis" :key="k.label">
                <div class="kpi-box">
                  <div class="kpi-label">{{ k.label }}</div>
                  <div class="kpi-value" :style="{ color: k.color || '#303133' }">{{ k.value }}</div>
                </div>
              </el-col>
            </el-row>

            <el-alert v-if="calcResult.overBudget" type="error" :closable="false" show-icon
                      class="mb12"
                      :title="`超季度预算：本季返利 ¥${fmt(calcResult.settlement.rebateAmt)} > 预算余额 ¥${fmt(calcResult.settlement.budgetRemain)}，提交后审批链自动升级销售总监（C-4.3-05）`" />

            <el-table :data="calcResult.segments || []" size="small" border class="mb12">
              <el-table-column type="index" label="#" width="50" />
              <el-table-column label="阶梯区间" width="150">
                <template #default="{ row }">
                  {{ pct(row.bandFrom) }} ~ {{ row.bandTo == null ? '无上限' : pct(row.bandTo) }}
                  <span v-if="row.bandTo != null && Number(row.usedTo) < Number(row.bandTo)"
                        style="color:#e6a23c">（用至 {{ pct(row.usedTo) }}）</span>
                </template>
              </el-table-column>
              <el-table-column label="段基数（目标×段宽）" width="170" align="right">
                <template #default="{ row }">¥{{ fmt(row.bandBase) }}</template>
              </el-table-column>
              <el-table-column label="段返利率" width="110" align="right">
                <template #default="{ row }">{{ Number(row.rebateRate) }}%</template>
              </el-table-column>
              <el-table-column label="段返利额" align="right">
                <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
              </el-table-column>
            </el-table>

            <div class="card-head">
              <span>
                合计返利 <b>¥{{ fmt(calcResult.settlement.rebateAmt) }}</b>
                ｜ 季度预算 ¥{{ fmt(calcResult.settlement.budgetAmt) }}
                ｜ 计算时点余额 ¥{{ fmt(calcResult.settlement.budgetRemain) }}
                ｜ 结算单 {{ (calcResult.settlement.settleNo || '') }}
                （{{ statusName(calcResult.settlement.status) }}）
              </span>
              <el-button size="small" type="primary" @click="openSubmit">提交审批</el-button>
            </div>
          </template>
        </el-card>

        <!-- 提交审批（超预算 → 必填说明与平衡方案） -->
        <el-dialog v-model="submitVisible" title="提交返利审批" width="560px">
          <el-alert v-if="isOver" type="error" :closable="false" show-icon class="mb12"
                    title="超季度预算：将自动升级销售总监（L2），须填写超预算原因说明与年度预算平衡方案（C-4.3-05）" />
          <el-form label-width="130px" size="small">
            <el-form-item label="返利金额">
              ¥{{ fmt((calcResult && calcResult.settlement || {}).rebateAmt) }}
            </el-form-item>
            <el-form-item v-if="isOver" label="超预算原因说明" required>
              <el-input v-model="submitForm.overReason" type="textarea" :rows="2"
                        placeholder="必填：为何超出本季预算（≥2 字）" />
            </el-form-item>
            <el-form-item v-if="isOver" label="年度预算平衡方案" required>
              <el-input v-model="submitForm.balancePlan" type="textarea" :rows="2"
                        placeholder="必填：年度预算如何平衡（≥2 字）" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="submitVisible = false">取消</el-button>
            <el-button type="primary" :loading="submitting" @click="doSubmit">提交</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ============ 配置 Tab（D13：不新增菜单行） ============ -->
      <el-tab-pane label="配置（目标/政策/预算）" name="config">
        <el-tabs v-model="cfgTab" type="card">
          <!-- ① 季度销售目标 -->
          <el-tab-pane label="① 季度销售目标" name="target">
            <el-card shadow="never">
              <template #header>
                <div class="card-head">
                  <b>季度销售目标（客户 × 季度）</b>
                  <el-button size="small" type="primary" @click="openTarget()">新增目标</el-button>
                </div>
              </template>
              <el-table :data="targets" size="small" border v-loading="loading">
                <el-table-column prop="customerCode" label="客户编码" width="130" />
                <el-table-column prop="customerName" label="客户" min-width="150" />
                <el-table-column prop="quarter" label="季度" width="100" />
                <el-table-column label="目标金额" width="140" align="right">
                  <template #default="{ row }">¥{{ fmt(row.targetAmt) }}</template>
                </el-table-column>
                <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
                <el-table-column label="操作" width="130">
                  <template #default="{ row }">
                    <el-button size="small" @click="openTarget(row)">编辑</el-button>
                    <el-button size="small" type="danger" @click="delTarget(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-tab-pane>

          <!-- ② 政策阶梯 -->
          <el-tab-pane label="② 返利政策阶梯" name="policy">
            <el-card shadow="never">
              <template #header>
                <div class="card-head">
                  <b>返利政策阶梯（超额累进；客户留空 = 全局默认）</b>
                  <el-button size="small" type="primary" @click="openPolicy()">新增阶梯</el-button>
                </div>
              </template>
              <el-table :data="policies" size="small" border v-loading="loading">
                <el-table-column prop="policyNo" label="政策编号" width="180" />
                <el-table-column prop="policyName" label="政策名称" min-width="140" />
                <el-table-column label="适用客户" width="160">
                  <template #default="{ row }">
                    <el-tag v-if="!row.customerId" size="small">全局默认</el-tag>
                    <span v-else>{{ row.customerId }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="达成率区间" width="170">
                  <template #default="{ row }">
                    {{ Number(row.bandFrom) }}% ~ {{ row.bandTo == null ? '无上限' : Number(row.bandTo) + '%' }}
                  </template>
                </el-table-column>
                <el-table-column label="返利率" width="90" align="right">
                  <template #default="{ row }">{{ Number(row.rebateRate) }}%</template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.status === 'EFFECTIVE' ? 'success' : 'info'">
                      {{ row.status === 'EFFECTIVE' ? '生效' : '停用' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="130">
                  <template #default="{ row }">
                    <el-button size="small" @click="openPolicy(row)">编辑</el-button>
                    <el-button size="small" type="danger" @click="delPolicy(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-tab-pane>

          <!-- ③ 年度返利预算 -->
          <el-tab-pane label="③ 年度返利预算" name="budget">
            <el-card shadow="never">
              <template #header>
                <div class="card-head">
                  <b>年度返利预算（按季度分解，FR-4.3-8-3）</b>
                  <div>
                    <el-select v-model="budgetYear" style="width:120px" @change="loadBudget">
                      <el-option v-for="y in years" :key="y" :label="`${y} 年`" :value="y" />
                    </el-select>
                    <el-button size="small" type="primary" style="margin-left:8px"
                               @click="openBudget">维护预算</el-button>
                  </div>
                </div>
              </template>
              <el-empty v-if="!budgetRow" description="本年度预算未维护" :image-size="70" />
              <template v-else>
                <el-row :gutter="12" class="mb12">
                  <el-col :span="6">
                    <div class="kpi-box"><div class="kpi-label">年度总额</div>
                      <div class="kpi-value">¥{{ fmt(budgetRow.totalAmt) }}</div></div>
                  </el-col>
                  <el-col :span="6" v-for="q in ['q1Amt','q2Amt','q3Amt','q4Amt']" :key="q">
                    <div class="kpi-box"><div class="kpi-label">{{ q.slice(0, 2).toUpperCase() }} 额度</div>
                      <div class="kpi-value">¥{{ fmt(budgetRow[q]) }}</div></div>
                  </el-col>
                </el-row>
                <el-table :data="budgetQuarters" size="small" border>
                  <el-table-column prop="quarter" label="季度" width="110" />
                  <el-table-column label="季度预算" width="140" align="right">
                    <template #default="{ row }">¥{{ fmt(row.budget) }}</template>
                  </el-table-column>
                  <el-table-column label="已占用（含审批中）" width="160" align="right">
                    <template #default="{ row }">¥{{ fmt(row.consumed) }}</template>
                  </el-table-column>
                  <el-table-column label="余额" align="right">
                    <template #default="{ row }">
                      <span :style="{ color: Number(row.remain) < 0 ? '#f56c6c' : '#67c23a' }">
                        ¥{{ fmt(row.remain) }}
                      </span>
                    </template>
                  </el-table-column>
                </el-table>
              </template>
            </el-card>
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>
    </el-tabs>

    <!-- 目标编辑弹窗 -->
    <el-dialog v-model="targetVisible" title="季度销售目标" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="客户" required>
          <el-select v-model="targetForm.customerId" filterable style="width:100%"
                     :disabled="!!targetForm.id">
            <el-option v-for="c in customers" :key="c.id"
                       :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="季度" required>
          <el-select v-model="targetForm.quarter" style="width:100%" :disabled="!!targetForm.id">
            <el-option v-for="q in quarters" :key="q" :label="q" :value="q" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标金额" required>
          <el-input-number v-model="targetForm.targetAmt" :min="0" :precision="2"
                           style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="targetForm.remark" placeholder="可空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="targetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveTarget">保存</el-button>
      </template>
    </el-dialog>

    <!-- 政策阶梯弹窗 -->
    <el-dialog v-model="policyVisible" title="返利政策阶梯" width="500px">
      <el-form label-width="120px" size="small">
        <el-form-item label="政策名称">
          <el-input v-model="policyForm.policyName" placeholder="如 经销商年度返利政策" />
        </el-form-item>
        <el-form-item label="适用客户">
          <el-select v-model="policyForm.customerId" clearable filterable style="width:100%"
                     placeholder="留空 = 全局默认政策">
            <el-option v-for="c in customers" :key="c.id"
                       :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="达成率下限%" required>
          <el-input-number v-model="policyForm.bandFrom" :min="0" :max="1000"
                           :precision="2" style="width:100%" />
        </el-form-item>
        <el-form-item label="达成率上限%">
          <el-input-number v-model="policyForm.bandTo" :min="0" :max="1000" :precision="2"
                           style="width:100%" placeholder="留空 = 无上限" />
          <span class="hint">留空表示无上限段（仅对落入该段的超额部分计返利）</span>
        </el-form-item>
        <el-form-item label="段返利率%" required>
          <el-input-number v-model="policyForm.rebateRate" :min="0" :max="100"
                           :precision="2" style="width:100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="policyForm.status">
            <el-radio value="EFFECTIVE">生效</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePolicy">保存</el-button>
      </template>
    </el-dialog>

    <!-- 预算弹窗 -->
    <el-dialog v-model="budgetVisible" :title="`${budgetYear} 年度返利预算`" width="520px">
      <el-form label-width="130px" size="small">
        <el-form-item label="年度总额" required>
          <el-input-number v-model="budgetForm.totalAmt" :min="0" :precision="2"
                           style="width:100%" @change="splitBudget" />
          <span class="hint">仅填总额时按季均分（25%×4）；四季填了必须合计等于总额</span>
        </el-form-item>
        <el-form-item label="Q1 额度"><el-input-number v-model="budgetForm.q1Amt"
          :min="0" :precision="2" style="width:100%" /></el-form-item>
        <el-form-item label="Q2 额度"><el-input-number v-model="budgetForm.q2Amt"
          :min="0" :precision="2" style="width:100%" /></el-form-item>
        <el-form-item label="Q3 额度"><el-input-number v-model="budgetForm.q3Amt"
          :min="0" :precision="2" style="width:100%" /></el-form-item>
        <el-form-item label="Q4 额度"><el-input-number v-model="budgetForm.q4Amt"
          :min="0" :precision="2" style="width:100%" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="budgetForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="budgetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBudget">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getTargetsApi, saveTargetApi, deleteTargetApi,
  getPoliciesApi, savePolicyApi, deletePolicyApi,
  getBudgetApi, saveBudgetApi, getBudgetSummaryApi,
  calculateRebateApi, submitSettlementApi
} from '@/api/sd/rebate'
import { getGroupPageApi } from '@/api/mdm/customer'

const mainTab = ref('calc')
const cfgTab = ref('target')
const loading = ref(false)
const calculating = ref(false)
const submitting = ref(false)
const saving = ref(false)

const customers = ref([])
const now = new Date()
const quarters = ref(Array.from({ length: 8 }, (_, i) => {
  const d = new Date(now.getFullYear(), now.getMonth() + i, 1)
  return `${d.getFullYear()}Q${Math.floor(d.getMonth() / 3) + 1}`
}).filter((q, i, a) => a.indexOf(q) === i))
const years = ref([now.getFullYear() - 1, now.getFullYear(), now.getFullYear() + 1])

const calcForm = ref({ customerId: '', quarter: quarters.value[3] || quarters.value[0] })
const calcResult = ref(null)
const submitVisible = ref(false)
const submitForm = ref({ overReason: '', balancePlan: '' })
const currentSettle = ref(null)

const targets = ref([])
const policies = ref([])
const budgetYear = ref(now.getFullYear())
const budgetRow = ref(null)
const budgetQuarters = ref([])
const targetVisible = ref(false)
const targetForm = ref({})
const policyVisible = ref(false)
const policyForm = ref({})
const budgetVisible = ref(false)
const budgetForm = ref({})

const isOver = computed(() =>
  currentSettle.value && String(currentSettle.value.overBudget) === '1')

const kpis = computed(() => {
  const s = (calcResult.value || {}).settlement
  if (!s) return []
  return [
    { label: '季度目标', value: `¥${fmt(s.targetAmt)}` },
    { label: '返利基数', value: `¥${fmt(s.baseAmt)}` },
    { label: '达成率', value: pct(s.achieveRate), color: '#409eff' },
    { label: '返利金额', value: `¥${fmt(s.rebateAmt)}`, color: '#e6a23c' },
    { label: '预算余额', value: `¥${fmt(s.budgetRemain)}`,
      color: Number(s.overBudget === '1') ? '#f56c6c' : '#67c23a' }
  ]
})

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function pct(v) {
  return `${(Number(v || 0) * 100).toFixed(2)}%`
}
function statusName(s) {
  return ({ DRAFT: '草稿', APPROVING: '审批中', APPROVED: '已通过',
    REJECTED: '已驳回', EXECUTED: '已执行' })[s] || s
}

// ---------- 计算 ----------
async function doCalculate() {
  if (!calcForm.value.customerId) return ElMessage.warning('请选择客户')
  calculating.value = true
  try {
    const res = await calculateRebateApi({ ...calcForm.value })
    calcResult.value = res.data
    if (res.data.pending) {
      ElMessage.warning('数据不完整，本季返利延期至数据补齐后计算')
    } else {
      ElMessage.success(`计算完成：达成率 ${pct(res.data.settlement.achieveRate)}`
        + `，返利 ¥${fmt(res.data.settlement.rebateAmt)}`
        + (res.data.overBudget ? '（超季度预算！）' : ''))
    }
    loadSummary()
  } finally {
    calculating.value = false
  }
}

function openSubmit() {
  currentSettle.value = calcResult.value.settlement
  submitForm.value = { overReason: '', balancePlan: '' }
  submitVisible.value = true
}

async function doSubmit() {
  if (!currentSettle.value) return
  if (isOver.value) {
    if (!submitForm.value.overReason || submitForm.value.overReason.trim().length < 2) {
      return ElMessage.warning('超预算须填写超预算原因说明（C-4.3-05）')
    }
    if (!submitForm.value.balancePlan || submitForm.value.balancePlan.trim().length < 2) {
      return ElMessage.warning('超预算须填写年度预算平衡方案（C-4.3-05）')
    }
  }
  submitting.value = true
  try {
    await submitSettlementApi(currentSettle.value.id, submitForm.value)
    ElMessage.success('已提交审批'
      + (isOver.value ? '（超预算，已升级销售总监）' : '（销售主管单节点）'))
    submitVisible.value = false
    await doCalculateKeep()
  } finally {
    submitting.value = false
  }
}

async function doCalculateKeep() {
  // 重算刷新状态（已提交单据重算会被拒，直接查详情）
  try {
    const res = await calculateRebateApi({ ...calcForm.value })
    calcResult.value = res.data
  } catch (e) {
    /* 已提交不可重算 → 列表页可见状态 */
  }
}

// ---------- 配置：目标 ----------
async function loadTargets() {
  loading.value = true
  try {
    const res = await getTargetsApi({ current: 1, size: 200 })
    targets.value = res.data.records || res.data || []
  } finally {
    loading.value = false
  }
}
function openTarget(row) {
  targetForm.value = row ? { ...row }
    : { customerId: '', quarter: quarters.value[0], targetAmt: 0, remark: '' }
  targetVisible.value = true
}
async function saveTarget() {
  saving.value = true
  try {
    await saveTargetApi(targetForm.value)
    ElMessage.success('目标已保存')
    targetVisible.value = false
    loadTargets()
  } finally {
    saving.value = false
  }
}
async function delTarget(row) {
  await ElMessageBox.confirm(`删除 ${row.customerName} ${row.quarter} 目标？`, '确认', { type: 'warning' })
  await deleteTargetApi(row.id)
  ElMessage.success('已删除')
  loadTargets()
}

// ---------- 配置：政策 ----------
async function loadPolicies() {
  const res = await getPoliciesApi({})
  policies.value = res.data || []
}
function openPolicy(row) {
  policyForm.value = row ? { ...row }
    : { policyName: '', customerId: '', bandFrom: 0, bandTo: null,
        rebateRate: 0, status: 'EFFECTIVE' }
  policyVisible.value = true
}
async function savePolicy() {
  saving.value = true
  try {
    await savePolicyApi(policyForm.value)
    ElMessage.success('政策阶梯已保存')
    policyVisible.value = false
    loadPolicies()
  } finally {
    saving.value = false
  }
}
async function delPolicy(row) {
  await ElMessageBox.confirm('删除该政策阶梯？', '确认', { type: 'warning' })
  await deletePolicyApi(row.id)
  ElMessage.success('已删除')
  loadPolicies()
}

// ---------- 配置：预算 ----------
async function loadBudget() {
  const res = await getBudgetApi(budgetYear.value)
  budgetRow.value = res.data || null
  loadSummary()
}
async function loadSummary() {
  const q = calcForm.value.quarter || `${budgetYear.value}Q1`
  const rows = []
  for (let i = 1; i <= 4; i++) {
    const quarter = `${budgetYear.value}Q${i}`
    const res = await getBudgetSummaryApi(budgetYear.value, quarter)
    rows.push({ quarter, ...res.data })
  }
  budgetQuarters.value = rows
  if (!calcForm.value.quarter) calcForm.value.quarter = q
}
function openBudget() {
  const b = budgetRow.value
  budgetForm.value = b
    ? { totalAmt: Number(b.totalAmt), q1Amt: Number(b.q1Amt), q2Amt: Number(b.q2Amt),
        q3Amt: Number(b.q3Amt), q4Amt: Number(b.q4Amt), remark: b.remark || '' }
    : { totalAmt: 0, q1Amt: 0, q2Amt: 0, q3Amt: 0, q4Amt: 0, remark: '' }
  budgetVisible.value = true
}
function splitBudget() {
  const t = Number(budgetForm.value.totalAmt || 0)
  const q = Math.round(t / 4 * 100) / 100
  budgetForm.value.q1Amt = q
  budgetForm.value.q2Amt = q
  budgetForm.value.q3Amt = q
  budgetForm.value.q4Amt = Math.round((t - q * 3) * 100) / 100
}
async function saveBudget() {
  saving.value = true
  try {
    await saveBudgetApi({ budgetYear: budgetYear.value, ...budgetForm.value })
    ElMessage.success('预算已保存')
    budgetVisible.value = false
    loadBudget()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  const res = await getGroupPageApi({ current: 1, size: 200 })
  customers.value = res.data.records || res.data || []
  loadTargets()
  loadPolicies()
  loadBudget()
})
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: 12px; color: #909399; }
.kpi-box { background: #f5f7fa; border-radius: 4px; padding: 10px 12px; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.kpi-value { font-size: 18px; font-weight: 600; }
</style>
