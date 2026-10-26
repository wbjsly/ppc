<template>
  <div class="page">
    <div class="head">
      <span class="title">9.1.3 合规校验</span>
      <div>
        <el-input v-model="period" placeholder="YYYYMM，默认当期" style="width: 130px; margin-right: 8px"
          size="small" />
        <el-button type="primary" size="small" @click="load">一键扫描</el-button>
      </div>
    </div>

    <el-alert v-if="report" :type="report.redCount > 0 ? 'error' : (report.warnCount > 0 ? 'warning' : 'success')"
      :closable="false" class="mb12"
      :title="report.redCount > 0
        ? `发现 ${report.redCount} 项红标问题、${report.warnCount} 项黄标提示`
        : (report.warnCount > 0 ? `${report.warnCount} 项黄标提示（无红标问题）` : '全部检查通过')" />

    <el-table v-if="items.length" :data="items" size="small" border>
      <el-table-column prop="key" label="检查项" width="200">
        <template #default="{ row }">{{ keyText(row.key) }}</template>
      </el-table-column>
      <el-table-column label="级别" width="80">
        <template #default="{ row }">
          <el-tag :type="levelTag(row.level)" size="small">{{ levelText(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="检查结果" min-width="280" />
      <el-table-column prop="advice" label="处置建议" min-width="220" />
      <template #append>
        <tr v-for="d in reconDetails" :key="d.period">
          <td colspan="5" class="diff-row">
            差异明细（期间 {{ d.period }}）：台账进项 {{ d.ledgerInput }} vs 总账 {{ d.glInput }}（差 {{ d.diffInput }}）；
            台账销项 {{ d.ledgerOutput }} vs 总账 {{ d.glOutput }}（差 {{ d.diffOutput }}）
          </td>
        </tr>
      </template>
    </el-table>
    <el-empty v-else description="点击「一键扫描」执行合规校验" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { complianceReport } from '@/api/fin/tax-calc'

const period = ref('')
const report = ref(null)
const items = computed(() => (report.value && report.value.items) || [])
const reconDetails = computed(() => {
  const recon = items.value.find(i => i.key === 'glRecon')
  return (recon && recon.details) || []
})

async function load() {
  try {
    const { data } = await complianceReport(period.value || undefined)
    report.value = data
    if (!period.value) period.value = data.period
  } catch (e) { /* 422 已提示 */ }
}

function keyText(k) {
  return {
    taxCodeExpiry: '税码过期/临期引用',
    taxCategoryCode: '税收分类编码登记',
    glRecon: '台账 vs 总账勾稽',
    nonDeductibleViolation: '简易计税抵扣违规'
  }[k] || k
}

function levelText(l) {
  return { ok: '通过', warn: '黄标', red: '红标' }[l] || l
}

function levelTag(l) {
  return { ok: 'success', warn: 'warning', red: 'danger' }[l] || 'info'
}

onMounted(() => { load() })
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.mb12 { margin-bottom: 12px; }
.diff-row { padding: 6px 12px; color: #f56c6c; font-size: 12px; background: #fef0f0; }
</style>
