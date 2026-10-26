<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">个税计算（10.5.2，累计预扣法）</span>
        <div>
          <el-input v-model="year" placeholder="年度" clearable style="width:100px;margin-right:8px" @change="load" />
          <el-button type="primary" @click="openSpecial">维护专项附加</el-button>
          <el-button @click="openBonus">年终奖试算</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                title="累计预扣法：本期税 = 累计应纳税所得额×预扣率 - 速算扣除数 - 累计已预扣；专项附加扣除由员工自助端维护+HR 代维护；税务系统实时同步为留桩（本期手工录入）" />
      <el-tabs>
        <el-tab-item label="税率表">
          <el-table :data="taxTable" height="320">
            <el-table-column prop="levelNo" label="级数" width="70" />
            <el-table-column prop="lowerBound" label="累计下限" width="120" />
            <el-table-column label="累计上限" width="120">
              <template #default="{ row }">{{ row.upperBound ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="预扣率" width="90">
              <template #default="{ row }">{{ (row.rate * 100).toFixed(1) }}%</template>
            </el-table-column>
            <el-table-column prop="quickDeduct" label="速算扣除数" width="120" />
          </el-table>
        </el-tab-item>
        <el-tab-item label="专项附加扣除">
          <el-table :data="specials" height="320">
            <el-table-column prop="empId" label="员工ID" width="200" />
            <el-table-column prop="deductType" label="类型" width="160" />
            <el-table-column prop="monthlyAmount" label="每月分摊" width="110" />
            <el-table-column prop="status" label="状态" width="90" />
          </el-table>
        </el-tab-item>
      </el-tabs>
    </el-card>

    <el-dialog v-model="specialVisible" title="维护专项附加扣除" width="420px">
      <el-form label-width="100px">
        <el-form-item label="员工ID"><el-input v-model="spForm.empId" /></el-form-item>
        <el-form-item label="扣除类型">
          <el-select v-model="spForm.deductType" style="width:100%">
            <el-option v-for="t in deductTypes" :key="t.v" :label="t.l" :value="t.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="每月金额"><el-input-number v-model="spForm.monthlyAmount" :min="0" :step="100" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="specialVisible = false">取消</el-button>
        <el-button type="primary" @click="doSaveSpecial">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="bonusVisible" title="年终奖计税试算（不落库）" width="400px">
      <el-form label-width="90px">
        <el-form-item label="员工ID"><el-input v-model="bonusForm.empId" /></el-form-item>
        <el-form-item label="金额"><el-input-number v-model="bonusForm.bonus" :min="0" :step="1000" /></el-form-item>
        <el-form-item label="计税方式">
          <el-radio-group v-model="bonusForm.choice">
            <el-radio value="SINGLE">单独计税</el-radio>
            <el-radio value="COMBINED">并入综合</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-alert v-if="bonusResult" type="success" :closable="false" style="margin-top:8px"
                :title="`预估税额 ¥${bonusResult.tax}，税后 ¥${bonusResult.afterTax}（${bonusResult.choice}）`" />
      <template #footer>
        <el-button @click="bonusVisible = false">关闭</el-button>
        <el-button type="primary" @click="doBonusCalc">试算</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { iitTaxTable, iitSpecialList, saveSpecialDeduction, bonusTaxCalc } from '@/api/hr/payroll'

const deductTypes = [
  { v: 'CHILD_EDUCATION', l: '子女教育' }, { v: 'CONTINUE_EDU', l: '继续教育' },
  { v: 'HOUSING_LOAN', l: '住房贷款利息' }, { v: 'HOUSING_RENT', l: '住房租金' },
  { v: 'SUPPORT_ELDERLY', l: '赡养老人' }, { v: 'INFANT_CARE', l: '3 岁以下婴幼儿照护' }
]
const year = ref(new Date().getFullYear())
const taxTable = ref([])
const specials = ref([])
const specialVisible = ref(false)
const spForm = ref({ empId: '', deductType: 'CHILD_EDUCATION', monthlyAmount: 1000 })
const bonusVisible = ref(false)
const bonusForm = ref({ empId: '', bonus: 60000, choice: 'SINGLE' })
const bonusResult = ref(null)

async function load() {
  const [t, s] = await Promise.all([iitTaxTable(year.value), iitSpecialList({})])
  taxTable.value = t.data || []
  specials.value = s.data || []
}
function openSpecial() { spForm.value = { empId: '', deductType: 'CHILD_EDUCATION', monthlyAmount: 1000 }; specialVisible.value = true }
async function doSaveSpecial() {
  if (!spForm.value.empId) return ElMessage.warning('员工ID必填')
  try {
    await saveSpecialDeduction(spForm.value)
    ElMessage.success('已保存'); specialVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
function openBonus() { bonusResult.value = null; bonusVisible.value = true }
async function doBonusCalc() {
  if (!bonusForm.value.empId) return ElMessage.warning('员工ID必填')
  const { data } = await bonusTaxCalc(bonusForm.value)
  bonusResult.value = data
}
onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
