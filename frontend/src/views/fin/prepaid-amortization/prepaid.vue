<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="hint"
      description="预提待摊（M8-9）：登记生成直线法计划（尾差并入末期，L1 合计校验）→ 每月「生成本期凭证」（幂等，DRAFT 待审核 FR-4.6-8-4）→ 预提实际冲销（差异>0.5% 自动标记 C-4.6-16）；年末余额检查为 L4 黄标提示。" />

    <!-- 全局工具条：期间 + 生成 + 年末检查（预提/待摊两页共用，状态见 useAmort.js） -->
    <el-form inline class="filter">
      <el-form-item label="期间">
        <el-input v-model="period" placeholder="yyyyMM" maxlength="6" style="width: 130px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="doGenerate">生成本期凭证</el-button>
        <el-button @click="doYearEndCheck">年末余额检查</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="genResult" :type="genResult.generated > 0 ? 'success' : 'info'" :closable="false" show-icon class="hint"
      :title="`生成完成：新增 ${genResult.generated} 张 DRAFT 凭证，跳过已生成 ${genResult.skipped} 行（幂等）——请前往凭证审核制单≠审核后过账`" />

    <el-card v-if="yearEnd" shadow="never" class="mb">
      <template #header>年末余额检查（L4 提示，FR-4.6-8-6 / BR-4.6-06）</template>
      <div v-for="it in yearEnd.items" :key="it.key" class="ye-row">
        <el-tag size="small" :type="it.ok ? 'success' : 'warning'">{{ it.ok ? '正常' : '黄标' }}</el-tag>
        <span class="ye-name">{{ it.name }}</span>
        <span class="muted">{{ it.detail }}</span>
      </div>
    </el-card>

    <!-- ============ 8.9.1 预提管理 ============ -->
    <el-form inline class="filter">
      <el-form-item>
        <el-button type="primary" @click="prepaidFormVisible = true">预提登记</el-button>
      </el-form-item>
    </el-form>
    <el-table :data="prepaidDocs" border stripe>
      <el-table-column prop="docNo" label="预提单号" width="170" />
      <el-table-column prop="accountCode" label="费用科目" width="90" />
      <el-table-column prop="accountName" label="科目名" min-width="130" show-overflow-tooltip />
      <el-table-column prop="period" label="归属期" width="90" />
      <el-table-column label="预提金额" width="120" align="right">
        <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'SETTLED' ? 'success' : 'info'">
            {{ row.status === 'SETTLED' ? '已冲销' : '未冲销' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'OPEN'" size="small" type="warning" @click="openSettle(row)">实际冲销</el-button>
          <el-button v-else size="small" @click="openPlans(row)">计划</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="prepaidFormVisible" title="预提费用登记" width="460px">
      <el-form label-width="90px">
        <el-form-item label="费用科目"><el-input v-model="prepaidForm.expenseAccountCode" placeholder="如 660203（DR 方向）" /></el-form-item>
        <el-form-item label="归属期间"><el-input v-model="prepaidForm.period" placeholder="yyyyMM" maxlength="6" /></el-form-item>
        <el-form-item label="预估金额"><el-input-number v-model="prepaidForm.amount" :precision="2" :min="0.01" style="width: 100%" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="prepaidForm.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="prepaidFormVisible = false">取消</el-button>
        <el-button type="primary" @click="doRegisterPrepaid">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="settleVisible" :title="`预提实际冲销 ${settleTarget?.docNo || ''}`" width="480px">
      <el-form label-width="100px">
        <el-form-item label="实际金额"><el-input-number v-model="settleForm.actualAmount" :precision="2" :min="0.01" style="width: 100%" /></el-form-item>
        <el-form-item label="冲销方式">
          <el-select v-model="settleForm.settleMode" style="width: 100%">
            <el-option label="付款（贷 1002 银行）" value="PAYMENT" />
            <el-option label="收票挂应付（贷 2202）" value="ON_ACCOUNT" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="settleForm.settleMode === 'PAYMENT'" label="付款账户">
          <el-select v-model="settleForm.bankAccountId" style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :label="`${a.accountName}（¥${fmt(a.balance)}）`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="冲销日期"><el-date-picker v-model="settleForm.payDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon
        title="差异率 > 0.5% 时凭证摘要自动标记差异率（C-4.6-16 计入当期费用留痕）" />
      <template #footer>
        <el-button @click="settleVisible = false">取消</el-button>
        <el-button type="primary" @click="doSettle">确认冲销</el-button>
      </template>
    </el-dialog>

    <!-- 计划下钻抽屉（预提/待摊两页共用） -->
    <AmortPlansDrawer />
  </div>
</template>

<script setup>
/**
 * 8.9.1 预提管理（拆分自原预提待摊多 tab 页，design D1）。
 * 全局工具条与单据列表见 useAmort.js；计划抽屉见 _parts/AmortPlansDrawer.vue。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getBankAccountsApi } from '@/api/fin/bank-account'
import { registerPrepaid, settlePrepaid } from '@/api/fin/amort'
import { useAmort } from './useAmort'
import { fmt } from './amort-helpers'
import AmortPlansDrawer from './_parts/AmortPlansDrawer.vue'

const {
  period, genResult, yearEnd, prepaidDocs, loadDocs,
  doGenerate, doYearEndCheck, openPlans
} = useAmort()

const accounts = ref([])
const prepaidFormVisible = ref(false)
const prepaidForm = ref({ expenseAccountCode: '660203', period: '', amount: undefined, remark: '' })
const settleVisible = ref(false)
const settleTarget = ref(null)
const settleForm = ref({ actualAmount: undefined, settleMode: 'PAYMENT', bankAccountId: '', payDate: new Date().toISOString().slice(0, 10) })

async function loadAccounts() {
  const res = await getBankAccountsApi()
  accounts.value = res.data || []
  if (accounts.value.length) settleForm.value.bankAccountId = accounts.value[0].id
}

async function doRegisterPrepaid() {
  await registerPrepaid(prepaidForm.value)
  ElMessage.success('预提已登记，计提计划行已生成')
  prepaidFormVisible.value = false
  loadDocs()
}
function openSettle(row) {
  settleTarget.value = row
  settleForm.value = { actualAmount: undefined, settleMode: 'PAYMENT', bankAccountId: settleForm.value.bankAccountId, payDate: new Date().toISOString().slice(0, 10) }
  settleVisible.value = true
}
async function doSettle() {
  const res = await settlePrepaid(settleTarget.value.id, settleForm.value)
  const d = res.data
  ElMessage.success(`已冲销，凭证 ${d.voucherNo}${d.overTolerance ? `（差异率超容差已标记 ${Number(d.varianceRate) * 100}%）` : ''}`)
  settleVisible.value = false
  loadDocs()
}

onMounted(() => {
  loadDocs()
  loadAccounts()
  prepaidForm.value.period = period.value
})
</script>

<style scoped>
.page { padding: 12px }
.hint { margin-bottom: 12px }
.filter { margin-bottom: 4px }
.mb { margin-bottom: 14px }
.muted { color: #909399; font-size: 12px }
.ye-row { display: flex; gap: 10px; align-items: center; margin-bottom: 8px }
.ye-name { font-weight: 600 }
</style>
