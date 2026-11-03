<template>
  <div class="page fx-reval-page">
    <!-- ================= 8.5.3 汇兑损益 ================= -->
    <el-form inline>
      <el-form-item label="会计期间">
        <el-input v-model="glPeriod" placeholder="YYYYMM（缺省当期）" style="width:150px" />
      </el-form-item>
      <el-button type="primary" @click="loadGainLoss" :loading="glLoading">查询</el-button>
    </el-form>
    <PageInfo title="6604 汇兑损益科目本期发生额（利润表口径）；已实现/未实现拆分不在本期范围（spec Out of Scope）。" style="margin-bottom:8px" />
    <el-descriptions v-if="glSummary" :column="3" border size="small">
      <el-descriptions-item label="本期汇兑损失（借方）">¥{{ fmt(glSummary.periodDebit) }}</el-descriptions-item>
      <el-descriptions-item label="本期汇兑收益（贷方）">¥{{ fmt(glSummary.periodCredit) }}</el-descriptions-item>
      <el-descriptions-item label="本期净额">
        <span :style="{ color: Number(glSummary.periodNet) >= 0 ? '#67c23a' : '#f56c6c' }">
          ¥{{ fmt(glSummary.periodNet) }}
        </span>
      </el-descriptions-item>
    </el-descriptions>
    <el-empty v-else description="点击查询加载 6604 科目余额" :image-size="80" />
  </div>
</template>

<script setup>
/**
 * 8.5.3 汇兑损益（拆分自原汇兑重估多 tab 页，design D1）。
 * 本页独有逻辑，金额格式化见 fx-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref } from 'vue'
import { gainLossSummary } from '@/api/fin/fx'
import { fmt } from './fx-helpers'

const glPeriod = ref('')
const glLoading = ref(false)
const glSummary = ref(null)

async function loadGainLoss() {
  glLoading.value = true
  try {
    const res = await gainLossSummary(glPeriod.value || undefined)
    glSummary.value = res.data
  } finally {
    glLoading.value = false
  }
}
</script>
