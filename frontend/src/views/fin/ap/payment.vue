<template>
  <div class="page ap-mgmt-page">
    <!-- ================= 8.3.3 付款管理（预测 + 计划） ================= -->
    <el-form inline>
      <el-button type="primary" @click="loadForecast">资金预测</el-button>
      <el-button type="success" @click="plCreateVisible = true">新建付款计划</el-button>
    </el-form>
    <template v-if="fcData">
      <el-table :data="fcData.windows" border size="small" style="margin-bottom:12px">
        <el-table-column prop="days" label="窗口（天）" width="110" />
        <el-table-column prop="apDue" label="到期未清应付" width="130" align="right" />
        <el-table-column prop="scheduled" label="已排期待付" width="120" align="right" />
        <el-table-column prop="prepay" label="预付款占用" width="120" align="right" />
        <el-table-column prop="need" label="资金需求合计" width="130" align="right" />
        <el-table-column prop="bank" label="银行可用余额" width="130" align="right" />
        <el-table-column label="资金缺口" width="120" align="right">
          <template #default="{ row }">
            <span :style="Number(row.gap) < 0 ? 'color:#f56c6c;font-weight:600' : 'color:#67c23a'">
              {{ row.gap }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <el-divider />
    <el-table :data="plRows" border size="small">
      <el-table-column prop="planNo" label="计划单号" width="160" show-overflow-tooltip />
      <el-table-column prop="period" label="期间" width="90" />
      <el-table-column prop="planTotal" label="计划总额" width="120" align="right" />
      <el-table-column prop="executedTotal" label="已执行" width="120" align="right" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onPlanDetail(row)">明细</el-button>
          <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="onSubmitPlan(row)">提交</el-button>
          <el-button v-if="row.status === 'PENDING'" link type="success" @click="onPlanApproval(row, true)">通过</el-button>
          <el-button v-if="row.status === 'PENDING'" link type="danger" @click="onPlanApproval(row, false)">驳回</el-button>
          <el-button v-if="row.status === 'APPROVED'" link type="warning" @click="onClosePlan(row)">关闭</el-button>
        </template>
      </el-table-column>
    </el-table>
        <AppPagination
      v-model:current-page="plQuery.current"
      v-model:page-size="plQuery.size"
      :total="plTotal"
      @current-change="loadPlans"
      style="margin-top:8px;justify-content:flex-end"
    />
    <el-dialog v-model="plCreateVisible" title="新建付款计划" width="520">
      <el-form label-width="90px">
        <el-form-item label="期间"><el-input v-model="plForm.period" placeholder="yyyyMM" maxlength="6" /></el-form-item>
        <el-form-item label="计划总额"><el-input-number v-model="plForm.planTotal" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="供应商行"><el-input v-model="plForm.planLines" type="textarea" :rows="4" placeholder='[{"supplierId":"S001","supplierName":"甲","planAmount":10000}]' /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="plCreateVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreatePlan">创建</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="plDetailVisible" title="计划明细（分供应商行）" width="560">
      <el-table :data="plDetailLines" border size="small">
        <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
        <el-table-column prop="planAmount" label="计划金额" width="120" align="right" />
        <el-table-column prop="executedAmount" label="已执行" width="120" align="right" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 8.3.3 付款管理（拆分自原应付管理多 tab 页，design D1）。
 * 本页独有逻辑，无跨页共享状态（D2：无共享未抽）。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  apForecast,
  createPlan, submitPlan, planApproval, closePlan, planPage, planDetail
} from '@/api/fin/ap'

const fcData = ref(null)
const plQuery = reactive({ current: 1, size: 20, period: '' })
const plRows = ref([])
const plTotal = ref(0)
const plCreateVisible = ref(false)
const plDetailVisible = ref(false)
const plDetailLines = ref([])
const plForm = reactive({ period: '', planTotal: 0, planLines: '' })

async function loadForecast() {
  const { data } = await apForecast({})
  fcData.value = data
}

async function loadPlans() {
  const { data } = await planPage({ ...plQuery, period: plQuery.period || undefined })
  plRows.value = data.records || []
  plTotal.value = Number(data.total || 0)
}

async function submitCreatePlan() {
  if (!plForm.period || plForm.period.length !== 6) {
    ElMessage.warning('期间须为 yyyyMM')
    return
  }
  let lines = null
  if (plForm.planLines.trim()) {
    try {
      lines = JSON.parse(plForm.planLines)
    } catch (e) {
      ElMessage.warning('供应商行 JSON 格式错误')
      return
    }
  }
  await createPlan({ period: plForm.period, planTotal: plForm.planTotal, planLines: JSON.stringify(lines) })
  ElMessage.success('计划已创建')
  plCreateVisible.value = false
  loadPlans()
}

async function onSubmitPlan(row) {
  await submitPlan(row.id)
  ElMessage.success('已提交审批')
  loadPlans()
}

async function onPlanApproval(row, approved) {
  await ElMessageBox.confirm(`${approved ? '通过' : '驳回'}付款计划 ${row.planNo}？`, '审批', { type: 'warning' })
  await planApproval(row.id, approved, approved ? null : '不通过')
  ElMessage.success('已处理')
  loadPlans()
}

async function onClosePlan(row) {
  await ElMessageBox.confirm(`关闭付款计划 ${row.planNo}？关闭后不可再执行付款。`, '关闭', { type: 'warning' })
  await closePlan(row.id)
  ElMessage.success('已关闭')
  loadPlans()
}

async function onPlanDetail(row) {
  const { data } = await planDetail(row.id)
  plDetailLines.value = data.lines || []
  plDetailVisible.value = true
}

onMounted(() => {
  loadPlans()
})
</script>
