<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="11.11.2 收款计划（合同维度分期，不参与记账）"
              description="按合同登记分期（期数/计划金额/到期日）；每期关联展示实际应收、已核销金额与达成率，超期未收标红（合同 → SO → 应收链路实时口径，D10）；手工登记实际收款走应收核销记录（不重复记账）。计划合计与合同额不符时给出待调整提示。" />

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <b>选择合同</b>
          <div>
            <el-select v-model="contractId" filterable placeholder="选择合同" size="small"
                       style="width:320px" @change="loadProgress">
              <el-option v-for="c in contracts" :key="c.id"
                         :label="`${c.contractNo} ${c.customerName} ¥${fmt(c.amount)}`"
                         :value="c.id" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="loadProgress">刷新</el-button>
          </div>
        </div>
      </template>
      <el-descriptions v-if="current" :column="4" size="small" border>
        <el-descriptions-item label="合同">{{ current.contractNo }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ current.customerName }}</el-descriptions-item>
        <el-descriptions-item label="合同金额">¥{{ fmt(current.amount) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small">{{ current.status }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-alert v-if="adjust && adjust.needed" type="warning" :closable="false" show-icon
              class="mb12"
              :title="`收款计划合计 ¥${fmt(adjust.planSum)} 与合同金额 ¥${fmt(adjust.contractAmount)} 不符（差额 ¥${fmt(adjust.diff)}）—— 请调整期次（14.6 变更后常见）`" />

    <el-row :gutter="12">
      <!-- 计划维护 -->
      <el-col :span="11">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>分期维护（全量保存）</b>
              <div>
                <el-button size="small" type="primary" :disabled="!current"
                           @click="addPeriod">+ 加期</el-button>
                <el-button size="small" type="success" :disabled="!current"
                           :loading="saving" @click="savePlans">保存</el-button>
              </div>
            </div>
          </template>
          <el-table :data="planRows" size="small" border>
            <el-table-column label="期数" width="80">
              <template #default="{ row, $index }">
                <el-input-number v-model="row.periodNo" :min="1" :controls="false"
                                 size="small" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="计划金额" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.planAmount" :min="0" :precision="2"
                                 size="small" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="计划到期日" width="150">
              <template #default="{ row }">
                <el-date-picker v-model="row.dueDate" type="date" value-format="YYYY-MM-DD"
                                size="small" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="备注">
              <template #default="{ row }">
                <el-input v-model="row.remark" size="small" placeholder="可空" />
              </template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ row, $index }">
                <el-button size="small" type="danger" text
                           @click="planRows.splice($index, 1)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="hint mt8">
            合计 ¥{{ fmt(planSum) }} / 合同 ¥{{ fmt(current ? current.amount : 0) }}
            <span v-if="current && Math.abs(planSum - Number(current.amount)) > 0.001"
                  style="color:#e6a23c">（不符，保存后提示待调整）</span>
          </div>
        </el-card>
      </el-col>

      <!-- 达成对比 -->
      <el-col :span="13">
        <el-card shadow="never">
          <template #header>
            <b>计划达成对比（实时口径，超期标红）</b>
          </template>
          <el-table :data="progressRows" size="small" border>
            <el-table-column prop="periodNo" label="期" width="50" align="center" />
            <el-table-column label="计划金额" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.planAmount) }}</template>
            </el-table-column>
            <el-table-column label="计划到期" width="105">
              <template #default="{ row }">
                <span :style="{ color: row.overdue ? '#f56c6c' : '#303133' }">
                  {{ row.dueDate }}</span>
              </template>
            </el-table-column>
            <el-table-column label="实际应收" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.arAmount) }}</template>
            </el-table-column>
            <el-table-column label="已核销" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.paidAmount) }}</template>
            </el-table-column>
            <el-table-column label="达成率" width="150">
              <template #default="{ row }">
                <el-progress :percentage="Math.min(100, Math.round(Number(row.rate) * 100))"
                             :stroke-width="12"
                             :status="row.overdue ? 'exception' : undefined" />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="rowStatusTag(row.status)">
                  {{ rowStatusName(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!progressRows.length" description="暂无计划或未选择合同"
                    :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 手工登记收款（关联应收核销记录） -->
    <el-card shadow="never" class="mt12">
      <template #header>
        <div class="card-head">
          <b>手工登记实际收款（关联应收核销，不重复记账）</b>
          <el-button size="small" @click="loadArs">刷新应收</el-button>
        </div>
      </template>
      <el-table :data="ars" size="small" border>
        <el-table-column prop="arNo" label="应收单号" width="170" />
        <el-table-column label="开票日" width="105">
          <template #default="{ row }">{{ row.invoiceDate }}</template>
        </el-table-column>
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="已核销" width="110" align="right">
          <template #default="{ row }">¥{{ fmt(row.paidAmount) }}</template>
        </el-table-column>
        <el-table-column label="余额" align="right">
          <template #default="{ row }">
            ¥{{ fmt(Number(row.amount) - Number(row.redAmount || 0) - Number(row.paidAmount || 0)) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary"
                       @click="openWriteoff(row)">登记收款</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!ars.length" description="该合同暂无应收（发货开票后产生）"
                :image-size="60" />
    </el-card>

    <!-- 登记收款弹窗 -->
    <el-dialog v-model="writeoffVisible" title="登记收款（人工核销）" width="460px">
      <el-descriptions v-if="writeoffRow" :column="2" size="small" border class="mb12">
        <el-descriptions-item label="应收单">{{ writeoffRow.arNo }}</el-descriptions-item>
        <el-descriptions-item label="可核销余额">
          ¥{{ fmt(Number(writeoffRow.amount) - Number(writeoffRow.redAmount || 0)
            - Number(writeoffRow.paidAmount || 0)) }}
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px" size="small">
        <el-form-item label="收款金额">
          <el-input-number v-model="writeoffForm.amount" :min="0" :precision="2"
                           style="width:100%" />
        </el-form-item>
        <el-form-item label="收款日期">
          <el-date-picker v-model="writeoffForm.payDate" type="date" value-format="YYYY-MM-DD"
                          style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="writeoffForm.remark" placeholder="可空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="writeoffVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doWriteoff">登记并核销</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getContractsApi, getContractDetailApi, savePlansApi, getProgressApi
} from '@/api/crm/contract'
import { getArPageApi, manualWriteoffApi } from '@/api/fin/ar'

const contracts = ref([])
const contractId = ref('')
const current = ref(null)
const planRows = ref([])
const progressRows = ref([])
const adjust = ref(null)
const ars = ref([])
const saving = ref(false)
const writeoffVisible = ref(false)
const writeoffRow = ref(null)
const writeoffForm = ref({ amount: 0, payDate: new Date().toISOString().slice(0, 10), remark: '' })

const planSum = computed(() =>
  planRows.value.reduce((s, r) => s + Number(r.planAmount || 0), 0))

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function rowStatusName(s) {
  return ({ RECEIVED: '已收足', OVERDUE: '超期未收', PENDING: '未到期' })[s] || s
}
function rowStatusTag(s) {
  return ({ RECEIVED: 'success', OVERDUE: 'danger', PENDING: 'info' })[s] || 'info'
}

async function load() {
  const res = await getContractsApi({ current: 1, size: 100 })
  contracts.value = res.data.records || res.data || []
  if (!contractId.value && contracts.value.length) {
    contractId.value = contracts.value[0].id
    loadProgress()
  }
}

async function loadProgress() {
  if (!contractId.value) return
  const d = (await getContractDetailApi(contractId.value)).data || {}
  current.value = d.contract
  planRows.value = (d.planProgress || []).map(p => ({
    periodNo: p.periodNo, planAmount: Number(p.planAmount),
    dueDate: p.dueDate, remark: ''
  }))
  const p = (await getProgressApi(contractId.value)).data || {}
  progressRows.value = p.planProgress || []
  adjust.value = p.planAdjust || null
  loadArs()
}

async function loadArs() {
  if (!contractId.value) return
  const res = await getArPageApi({ current: 1, size: 100, contractId: contractId.value })
  ars.value = res.data.records || res.data || []
}

function addPeriod() {
  const next = planRows.value.length
    ? Math.max(...planRows.value.map(r => Number(r.periodNo) || 0)) + 1 : 1
  planRows.value.push({ periodNo: next, planAmount: 0, dueDate: '', remark: '' })
}

async function savePlans() {
  if (!planRows.value.length) return ElMessage.warning('至少一期')
  saving.value = true
  try {
    const res = await savePlansApi(contractId.value, planRows.value)
    const d = res.data || {}
    ElMessage.success(d.adjustNeeded
      ? `已保存（合计 ¥${fmt(d.planSum)} 与合同额不符，差额 ¥${fmt(d.adjustDiff)}，待调整）`
      : '收款计划已保存')
    loadProgress()
  } finally {
    saving.value = false
  }
}

function openWriteoff(row) {
  writeoffRow.value = row
  const bal = Number(row.amount) - Number(row.redAmount || 0) - Number(row.paidAmount || 0)
  writeoffForm.value = {
    amount: Math.max(0, bal),
    payDate: new Date().toISOString().slice(0, 10),
    remark: '收款计划页登记'
  }
  writeoffVisible.value = true
}

async function doWriteoff() {
  saving.value = true
  try {
    await manualWriteoffApi({
      arId: writeoffRow.value.id,
      amount: writeoffForm.value.amount,
      payDate: writeoffForm.value.payDate,
      remark: writeoffForm.value.remark
    })
    ElMessage.success('已登记收款并生成核销记录（不重复记账）')
    writeoffVisible.value = false
    loadProgress()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.mt8 { margin-top: 8px; }
.mt12 { margin-top: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: 12px; color: #909399; }
</style>
