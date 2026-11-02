<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="hint"
      description="费用报销（M8-8）：报销提交（发票重复 L1 阻断）→ 部门主管+财务两节点审批 → 系统自动落费用凭证与 FIFO 借款冲销（BR-4.6-01）→ 差额由出纳补付/退款（前款不清、后款不借：超期借款新增申请自动加签财务主管）。" />

    <!-- ============ 8.8.1 报销申请 ============ -->
    <el-form inline class="filter">
      <el-form-item label="状态">
        <el-select v-model="reimFilter.status" clearable placeholder="全部" style="width: 150px" @change="loadReimburses">
          <el-option label="审批中" value="PENDING" />
          <el-option label="已审批" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="openSubmit">提交报销</el-button>
        <el-button @click="typesVisible = true">费用类型维护</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="reimburses" border stripe>
      <el-table-column prop="expNo" label="报销单号" width="170" />
      <el-table-column prop="employeeName" label="申请人" width="110" />
      <el-table-column label="金额" width="110" align="right">
        <template #default="{ row }">¥{{ fmt(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="reimbTag(row.status)">{{ reimbName(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结算" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.settleStatus === 'DONE' ? 'success' : row.settleStatus === 'PENDING' ? 'warning' : 'info'">
            {{ { NONE: '未到结算', PENDING: '待补付', DONE: '已结算' }[row.settleStatus] || row.settleStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="occurPeriod" label="归属期" width="90" />
      <el-table-column prop="createDate" label="提交时间" width="170" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 提交对话框 -->
    <el-dialog v-model="submitVisible" title="提交费用报销单" width="760px" top="4vh">
      <el-form label-width="90px">
        <el-form-item label="费用明细">
          <div style="width: 100%">
            <div v-for="(ln, i) in formLines" :key="i" class="line-row">
              <el-select v-model="ln.expenseType" placeholder="费用类型" style="width: 130px">
                <el-option v-for="t in expenseTypes" :key="t.typeCode"
                  :label="`${t.typeName}${t.status !== 'ACTIVE' ? '（停用）' : ''}`" :value="t.typeCode"
                  :disabled="t.status !== 'ACTIVE'" />
              </el-select>
              <el-input-number v-model="ln.amount" :precision="2" :min="0.01" placeholder="金额" style="width: 130px" />
              <el-date-picker v-model="ln.occurDate" type="date" value-format="YYYY-MM-DD" placeholder="发生日期" style="width: 150px" />
              <el-input v-model="ln.invoiceNo" placeholder="发票号（重复将阻断）" style="width: 180px" />
              <el-button v-if="formLines.length > 1" link type="danger" @click="formLines.splice(i, 1)">删除</el-button>
            </div>
            <el-button link type="primary" @click="formLines.push({ expenseType: '', amount: undefined, occurDate: today(), invoiceNo: '' })">+ 加一行</el-button>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="submitForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false" show-icon
        title="提交即校验：发票号历史重复将 L1 阻断并展示原单号（C-4.6-13）；类型映射缺失 422" />
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" @click="doSubmit">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- 类型维护抽屉 -->
    <el-drawer v-model="typesVisible" title="费用类型-科目映射维护" size="560px">
      <div style="margin-bottom: 10px">
        <el-button size="small" type="primary" @click="openTypeForm()">新增类型</el-button>
      </div>
      <el-table :data="expenseTypes" border size="small">
        <el-table-column prop="typeCode" label="编码" width="110" />
        <el-table-column prop="typeName" label="名称" width="120" />
        <el-table-column prop="accountCode" label="科目" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTypeForm(row)">编辑</el-button>
            <el-button link :type="row.status === 'ACTIVE' ? 'warning' : 'success'" size="small"
              @click="toggleType(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-form v-if="typeFormVisible" label-width="80px" style="margin-top: 14px">
        <el-form-item label="编码"><el-input v-model="typeForm.typeCode" :disabled="!!typeForm.id" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="typeForm.typeName" /></el-form-item>
        <el-form-item label="费用科目"><el-input v-model="typeForm.accountCode" placeholder="如 660202（须 DR 方向）" /></el-form-item>
        <el-button type="primary" size="small" @click="saveType">保存</el-button>
      </el-form>
    </el-drawer>

    <!-- 详情抽屉（报销申请/借款冲销两页共用） -->
    <ReimburseDetailDrawer />
  </div>
</template>

<script setup>
/**
 * 8.8.1 报销申请（拆分自原费用报销多 tab 页，design D1）。
 * 报销详情抽屉见 _parts/ReimburseDetailDrawer.vue（状态在 useExpense.js）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  submitReimburse, listReimburses,
  listExpenseTypes, saveExpenseType, updateExpenseTypeStatus
} from '@/api/fin/expense-reimbursement'
import { useExpense } from './useExpense'
import { today, fmt, reimbName } from './expense-helpers'
import ReimburseDetailDrawer from './_parts/ReimburseDetailDrawer.vue'

const { openDetail } = useExpense()

function reimbTag(s) { return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' }[s] || 'info' }

// ---- 报销 ----
const reimburses = ref([])
const reimFilter = ref({ status: '' })
const submitVisible = ref(false)
const submitForm = ref({ remark: '' })
const formLines = ref([])
const expenseTypes = ref([])
const typesVisible = ref(false)
const typeFormVisible = ref(false)
const typeForm = ref({})

async function loadReimburses() {
  const res = await listReimburses({ status: reimFilter.value.status || undefined })
  reimburses.value = res.data || []
}
function openSubmit() {
  submitForm.value = { remark: '' }
  formLines.value = [{ expenseType: '', amount: undefined, occurDate: today(), invoiceNo: '' }]
  submitVisible.value = true
}
async function doSubmit() {
  const total = formLines.value.reduce((s, l) => s + (Number(l.amount) || 0), 0)
  if (total <= 0) { ElMessage.warning('明细金额合计须大于 0'); return }
  await submitReimburse({ totalAmount: total, remark: submitForm.value.remark, lines: formLines.value })
  ElMessage.success('已提交审批（部门主管 → 财务审核）')
  submitVisible.value = false
  loadReimburses()
}
async function loadTypes() {
  const res = await listExpenseTypes()
  expenseTypes.value = res.data || []
}
function openTypeForm(row) {
  typeForm.value = row ? { ...row } : { typeCode: '', typeName: '', accountCode: '' }
  typeFormVisible.value = true
}
async function saveType() {
  await saveExpenseType(typeForm.value)
  ElMessage.success('映射已保存')
  typeFormVisible.value = false
  loadTypes()
}
async function toggleType(row) {
  await updateExpenseTypeStatus(row.id, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
  loadTypes()
}

onMounted(() => {
  loadReimburses()
  loadTypes()
})
</script>

<style scoped>
.page { padding: 12px }
.hint { margin-bottom: 12px }
.filter { margin-bottom: 4px }
.line-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center }
</style>
