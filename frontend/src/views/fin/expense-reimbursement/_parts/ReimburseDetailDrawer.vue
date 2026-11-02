<template>
  <!-- 报销详情抽屉（8.8.1 报销申请 / 8.8.3 借款冲销 两页共用） -->
  <el-drawer v-model="detailVisible" :title="`报销详情 ${detail.expNo || ''}`" size="640px">
    <template v-if="detail.id">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="申请人">{{ detail.employeeName }}</el-descriptions-item>
        <el-descriptions-item label="总额">¥{{ fmt(detail.totalAmount) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ reimbName(detail.status) }}</el-descriptions-item>
        <el-descriptions-item label="结算状态">{{ detail.settleStatus }}</el-descriptions-item>
      </el-descriptions>
      <el-divider content-position="left">明细行</el-divider>
      <el-table :data="detail.lines || []" border size="small">
        <el-table-column prop="expenseType" label="类型" width="90" />
        <el-table-column label="金额" width="100" align="right">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="invoiceNo" label="发票号" width="150" />
        <el-table-column prop="occurDate" label="发生日" width="100" />
      </el-table>
      <el-divider content-position="left">借款冲销（FIFO）</el-divider>
      <el-table :data="detail.writeoffs || []" border size="small">
        <el-table-column prop="loanNo" label="借款单" width="150" />
        <el-table-column label="冲销金额" width="110" align="right">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="writeoffDate" label="冲销日" width="100" />
      </el-table>
      <p v-if="!(detail.writeoffs || []).length" class="muted">无未核销借款，全额挂应付 2241</p>
    </template>
  </el-drawer>
</template>

<script setup>
/**
 * 报销详情抽屉（跨页共用，design D2：状态放 useExpense.js 单例）。
 * 供报销申请页「详情」与借款冲销页「冲销明细」渲染，接口与展示不变。
 */
import { useExpense } from '../useExpense'
import { fmt, reimbName } from '../expense-helpers'

const { detailVisible, detail } = useExpense()
</script>

<style scoped>
.muted { color: #909399; font-size: 12px }
</style>
