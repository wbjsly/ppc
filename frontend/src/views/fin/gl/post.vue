<template>
  <div class="page gl-page">
    <!-- ================= 8.1.4 凭证过账 ================= -->
    <el-form inline>
      <el-button type="primary" @click="loadManual('AUDITED')">刷新待过账</el-button>
      <el-divider direction="vertical" />
      <el-input v-model="balQuery.period" placeholder="YYYYMM" style="width:110px" />
      <el-input v-model="balQuery.accountCode" clearable placeholder="科目编码" style="width:130px" />
      <el-button type="warning" @click="loadBalances">查询余额</el-button>
    </el-form>
    <el-table :data="postRows" border size="small" style="margin-bottom:16px">
      <el-table-column prop="voucherNo" label="凭证号" width="160" />
      <el-table-column prop="voucherDate" label="日期" width="110" />
      <el-table-column prop="auditBy" label="审核人" width="110" />
      <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip />
      <el-table-column prop="totalDr" label="金额" width="110" />
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button size="small" type="warning" @click="doPost(row.id)">过账</el-button>
        </template>
      </el-table-column>
    </el-table>
    <h4 v-if="balRows.length" style="margin:8px 0">科目余额（{{ balQuery.period }}）</h4>
    <el-table v-if="balRows.length" :data="balRows" border size="small">
      <el-table-column prop="ACCOUNT_CODE" label="科目" width="100" />
      <el-table-column prop="ACCOUNT_NAME" label="名称" min-width="150" />
      <el-table-column prop="AUX_KEY" label="辅助维度" width="150">
        <template #default="{ row }">{{ row.AUX_KEY || '—' }}</template>
      </el-table-column>
      <el-table-column prop="openBalance" label="期初" width="110" />
      <el-table-column prop="DR_TOTAL" label="本期借" width="110" />
      <el-table-column prop="CR_TOTAL" label="本期贷" width="110" />
      <el-table-column prop="closeBalance" label="期末" width="110" />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 8.1.4 凭证过账（拆分自原总账多 tab 页，design D1）。
 * 待过账列表/过账动作见 useGl.js。
 * 原组件挂载仅加载科目/草稿/模板/期间，postRows 由「刷新待过账」按需加载（保持原行为）。
 */
import { ref, reactive } from 'vue'
import { balanceQuery } from '@/api/fin/gl'
import { useGl } from './useGl'

const { postRows, loadManual, doPost } = useGl()

// ---------- 余额 ----------
const balQuery = reactive({ period: new Date().toISOString().slice(0, 7).replace('-', ''), accountCode: '' })
const balRows = ref([])
async function loadBalances() {
  const { data } = await balanceQuery({
    period: balQuery.period, accountCode: balQuery.accountCode || undefined
  })
  balRows.value = data || []
}
</script>
