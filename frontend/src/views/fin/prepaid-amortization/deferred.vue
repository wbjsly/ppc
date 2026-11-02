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

    <!-- ============ 8.9.2 待摊管理 ============ -->
    <el-form inline class="filter">
      <el-form-item>
        <el-button type="primary" @click="deferredFormVisible = true">待摊登记</el-button>
      </el-form-item>
    </el-form>
    <el-table :data="deferredDocs" border stripe>
      <el-table-column prop="docNo" label="待摊单号" width="170" />
      <el-table-column prop="summary" label="事项" min-width="150" show-overflow-tooltip />
      <el-table-column label="总金额" width="120" align="right">
        <template #default="{ row }">¥{{ fmt(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="月数" width="70" align="right" prop="amortMonths" />
      <el-table-column prop="startPeriod" label="起始期" width="90" />
      <el-table-column label="进度" width="110">
        <template #default="{ row }">
          <el-progress :percentage="Math.round((row.generatedCount / row.amortMonths) * 100)"
            :status="row.status === 'COMPLETED' ? 'success' : undefined" :stroke-width="12" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openPlans(row)">计划</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="deferredFormVisible" title="待摊费用登记（直线法摊销）" width="480px">
      <el-form label-width="100px">
        <el-form-item label="事项说明"><el-input v-model="deferredForm.summary" placeholder="如：办公房租" /></el-form-item>
        <el-form-item label="总金额"><el-input-number v-model="deferredForm.totalAmount" :precision="2" :min="0.01" style="width: 100%" /></el-form-item>
        <el-form-item label="摊销月数"><el-input-number v-model="deferredForm.amortMonths" :min="1" style="width: 100%" /></el-form-item>
        <el-form-item label="起始期间"><el-input v-model="deferredForm.startPeriod" placeholder="yyyyMM" maxlength="6" /></el-form-item>
        <el-form-item label="费用科目"><el-input v-model="deferredForm.expenseAccountCode" placeholder="如 660203" /></el-form-item>
        <el-form-item label="待摊科目"><el-input v-model="deferredForm.assetAccountCode" placeholder="默认 1801" /></el-form-item>
      </el-form>
      <el-alert v-if="deferredL4" type="warning" :closable="false" show-icon :title="deferredL4" />
      <template #footer>
        <el-button @click="deferredFormVisible = false">取消</el-button>
        <el-button type="primary" @click="doRegisterDeferred">登记并生成计划</el-button>
      </template>
    </el-dialog>

    <!-- 计划下钻抽屉（预提/待摊两页共用） -->
    <AmortPlansDrawer />
  </div>
</template>

<script setup>
/**
 * 8.9.2 待摊管理（拆分自原预提待摊多 tab 页，design D1）。
 * 全局工具条与单据列表见 useAmort.js；计划抽屉见 _parts/AmortPlansDrawer.vue。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { registerDeferred } from '@/api/fin/amort'
import { useAmort } from './useAmort'
import { fmt } from './amort-helpers'
import AmortPlansDrawer from './_parts/AmortPlansDrawer.vue'

const {
  period, genResult, yearEnd, deferredDocs, loadDocs,
  doGenerate, doYearEndCheck, openPlans
} = useAmort()

const deferredFormVisible = ref(false)
const deferredForm = ref({ summary: '', totalAmount: undefined, amortMonths: 12, startPeriod: '', expenseAccountCode: '660203', assetAccountCode: '1801' })
const deferredL4 = ref('')

async function doRegisterDeferred() {
  deferredL4.value = ''
  const res = await registerDeferred(deferredForm.value)
  if (res.data.l4Hint) deferredL4.value = res.data.l4Hint
  ElMessage.success(`待摊已登记（${res.data.amortMonths} 期计划）${res.data.l4Hint ? '；' + res.data.l4Hint : ''}`)
  deferredFormVisible.value = false
  loadDocs()
}

onMounted(() => {
  loadDocs()
  deferredForm.value.startPeriod = period.value
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
