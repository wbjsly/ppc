<template>
  <div class="app-container">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="价控校验（2.3.5，BR-4.2-15~19 / C-4.2-06 / C-4.2-03）"
      description="三重固定优先级逐级降级：合同价(框架协议价，偏差 D1) > 历史价(近3次已下达均价，0次放行·偏差 D2) > 预算(科目本年累计 vs 预算×110%)；命中即停，全程落日志。阻断可走「申请特批」转升级链（FR-4.2-3-1）。" />

    <el-tabs v-model="tab">
      <!-- ============ 价控日志 ============ -->
      <el-tab-pane label="校验日志" name="logs">
        <div class="filter-bar">
          <el-select v-model="selectedPo" placeholder="选择订单" filterable style="width: 340px;" @change="loadLogs">
            <el-option v-for="o in poList" :key="o.id" :label="`${o.poNo} ${o.supplierName} ¥${o.totalAmt}`" :value="o.id" />
          </el-select>
          <el-button type="primary" :disabled="!selectedPo" @click="loadLogs">查询日志</el-button>
        </div>

        <el-table :data="logs" v-loading="loading" border>
          <el-table-column label="级别" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ CONTRACT: 'success', HISTORY: 'warning', BUDGET: 'danger' }[row.checkLevel]">
                {{ { CONTRACT: '①合同价', HISTORY: '②历史价', BUDGET: '③预算' }[row.checkLevel] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="lineNo" label="行" width="60" align="center">
            <template #default="{ row }">{{ row.lineNo || 'PO级' }}</template>
          </el-table-column>
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="baseValue" label="基准值" width="120" align="right" />
          <el-table-column prop="actualValue" label="实际值" width="120" align="right" />
          <el-table-column label="结论" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="pcType(row.result)">{{ pcLabel(row.result) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="说明" min-width="260" show-overflow-tooltip />
          <el-table-column prop="chkDate" label="时间" width="160" />
        </el-table>

        <el-descriptions :column="3" border size="small" style="margin-top: 14px;" title="三重参数（application.yml，参数化禁硬编码）">
          <el-descriptions-item label="PRICE_TOLERANCE">5%（合同/协议价上浮容差，C-4.2-06）</el-descriptions-item>
          <el-descriptions-item label="TOLERANCE_DEFAULT">0.5%（历史均价偏差，BR-4.2-16）</el-descriptions-item>
          <el-descriptions-item label="PURCHASE_BUDGET_TRIGGER">110%（预算升级阈值，BR-4.2-17）</el-descriptions-item>
          <el-descriptions-item label="优先级">合同价 → 历史价 → 预算，命中即停（BR-4.2-19）</el-descriptions-item>
          <el-descriptions-item label="冷启动">历史 0 次 NO_HISTORY 放行（偏差 D2）</el-descriptions-item>
          <el-descriptions-item label="合同价数据源">框架协议价（11.11 未实现，偏差 D1）</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <!-- ============ 预算维护 ============ -->
      <el-tab-pane label="年度预算" name="budget">
        <div class="filter-bar">
          <el-date-picker v-model="budgetYear" type="year" placeholder="年度" style="width: 120px;" @change="loadBudgets" />
          <el-input v-model="budgetKeyword" placeholder="科目编码/名称" clearable style="width: 200px;" @keyup.enter="loadBudgets" />
          <el-button type="primary" @click="loadBudgets">查询</el-button>
          <el-button type="success" @click="openBudget()">录入预算</el-button>
        </div>
        <el-table :data="budgets" v-loading="loading" border>
          <el-table-column prop="budgetYear" label="年度" width="80" align="center" />
          <el-table-column prop="categoryCode" label="品类科目" width="110" />
          <el-table-column prop="categoryName" label="名称" min-width="120" />
          <el-table-column prop="budgetAmt" label="预算金额" width="130" align="right" />
          <el-table-column prop="usedAmt" label="本年累计" width="130" align="right" />
          <el-table-column prop="remainAmt" label="剩余" width="130" align="right" />
          <el-table-column prop="triggerAmt" label="110% 阈值" width="130" align="right" />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.budgetFlag === 'NO_BUDGET' ? 'danger' : (Number(row.remainAmt) < 0 ? 'warning' : 'success')">
                {{ row.budgetFlag === 'NO_BUDGET' ? '未设预算' : (Number(row.remainAmt) < 0 ? '已超支' : '正常') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="changeReason" label="最近变更" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.budgetFlag !== 'NO_BUDGET'" link type="primary" @click="openBudget(row)">修改</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="hint" style="margin-top: 8px;">口径：本年累计 = 当年"已批准"PO 按物料品类汇总（草稿/驳回/已关闭不计）；未设预算科目下单放行记 NO_BUDGET 日志，但超 110% 触发审批升级</div>
      </el-tab-pane>
    </el-tabs>

    <!-- 预算录入/修改 -->
    <el-dialog v-model="budgetVisible" :title="budgetForm.id ? '修改预算' : '录入预算'" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="预算年度" required>
          <el-input-number v-model="budgetForm.budgetYear" :min="2000" :max="2100" />
        </el-form-item>
        <el-form-item label="品类科目" required>
          <el-select v-model="budgetForm.categoryCode" filterable allow-create default-first-option placeholder="选择或输入科目" style="width: 100%;">
            <el-option v-for="c in categories" :key="c.categoryCode" :label="[c.categoryCode, c.categoryName].join(' ')" :value="c.categoryCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="预算金额" required>
          <el-input-number v-model="budgetForm.budgetAmt" :min="0" :precision="2" style="width: 100%;" />
        </el-form-item>
        <el-form-item :label="budgetForm.id ? '变更原因' : '备注'" :required="!!budgetForm.id">
          <el-input v-model="budgetForm.changeReason" :placeholder="budgetForm.id ? '修改必填（≥2 字）留痕前后值' : '可选'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="budgetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBudget">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getPoPageApi } from '@/api/proc/purchase-order'
import { getPriceLogsApi } from '@/api/proc/purchase-order'
import { getBudgetListApi, createBudgetApi, updateBudgetApi } from '@/api/proc/purchase-budget'

const tab = ref('logs')
const loading = ref(false)
const saving = ref(false)

// ---- 日志 ----
const poList = ref([])
const selectedPo = ref('')
const logs = ref([])

async function loadLogs() {
  if (!selectedPo.value) return
  loading.value = true
  try {
    const { data } = await getPriceLogsApi(selectedPo.value)
    logs.value = data || []
  } finally {
    loading.value = false
  }
}

// ---- 预算 ----
const budgetYear = ref(new Date().getFullYear())
const budgetKeyword = ref('')
const budgets = ref([])
const categories = ref([
  { categoryCode: '0001', categoryName: '原材料' }
])
const budgetVisible = ref(false)
const budgetForm = reactive({ id: null, budgetYear: new Date().getFullYear(), categoryCode: '', budgetAmt: 0, changeReason: '' })

async function loadBudgets() {
  loading.value = true
  try {
    const { data } = await getBudgetListApi({ year: budgetYear.value, keyword: budgetKeyword.value })
    budgets.value = data || []
  } finally {
    loading.value = false
  }
}

function openBudget(row) {
  if (row) {
    Object.assign(budgetForm, {
      id: row.id, budgetYear: row.budgetYear, categoryCode: row.categoryCode,
      budgetAmt: Number(row.budgetAmt), changeReason: ''
    })
  } else {
    Object.assign(budgetForm, {
      id: null, budgetYear: budgetYear.value, categoryCode: '',
      budgetAmt: 0, changeReason: ''
    })
  }
  budgetVisible.value = true
}

async function saveBudget() {
  if (!budgetForm.categoryCode) { ElMessage.warning('请选择品类科目'); return }
  if (budgetForm.id && (!budgetForm.changeReason || budgetForm.changeReason.trim().length < 2)) {
    ElMessage.warning('修改须填变更原因（≥2 字）'); return
  }
  saving.value = true
  try {
    if (budgetForm.id) {
      await updateBudgetApi(budgetForm.id, { ...budgetForm })
      ElMessage.success('已修改并留痕')
    } else {
      await createBudgetApi({ ...budgetForm })
      ElMessage.success('已录入')
    }
    budgetVisible.value = false
    loadBudgets()
  } finally {
    saving.value = false
  }
}

function pcLabel(r) {
  return { PASS: '通过', ESCALATE: '升级', BLOCK: '阻断',
    NO_HISTORY: '无历史放行', NO_BUDGET: '未设预算放行' }[r] || r || '—'
}
function pcType(r) {
  return { PASS: 'success', ESCALATE: 'warning', BLOCK: 'danger',
    NO_HISTORY: 'info', NO_BUDGET: 'info' }[r] || 'info'
}

onMounted(async () => {
  const { data } = await getPoPageApi({ current: 1, size: 100 })
  poList.value = data.records || []
  loadBudgets()
})
</script>

<style scoped>
.filter-bar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }
</style>
