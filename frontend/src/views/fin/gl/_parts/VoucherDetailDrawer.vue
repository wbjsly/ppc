<template>
  <!-- 凭证详情/打印抽屉（8.1.2 凭证录入 / 8.1.3 凭证审核 两页共用） -->
  <el-drawer v-model="detailVisible" title="凭证详情" size="720px">
    <template v-if="detail">
      <div class="print-area">
        <div style="text-align:center;font-size:18px;font-weight:bold;margin-bottom:12px">记 账 凭 证</div>
        <el-descriptions :column="3" border size="small" class="no-print">
          <el-descriptions-item label="凭证号">{{ detail.voucherNo }}</el-descriptions-item>
          <el-descriptions-item label="日期">{{ detail.voucherDate }}</el-descriptions-item>
          <el-descriptions-item label="期间">{{ detail.period }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
          <el-descriptions-item label="制单人">{{ detail.createBy }}</el-descriptions-item>
          <el-descriptions-item label="审核人">{{ detail.auditBy || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.lines || []" border size="small" style="margin-top:10px">
          <el-table-column type="index" label="行" width="50" />
          <el-table-column prop="accountCode" label="科目" width="90" />
          <el-table-column prop="accountName" label="科目名称" min-width="140" />
          <el-table-column prop="direction" label="方向" width="60" />
          <el-table-column prop="amount" label="金额" width="110" />
          <el-table-column prop="summary" label="摘要" min-width="120" />
        </el-table>
        <div style="margin-top:10px;display:flex;justify-content:space-between">
          <span>合计：借 {{ detail.totalDr }} / 贷 {{ detail.totalCr }}</span>
          <span>制单：{{ detail.createBy }}　　审核：{{ detail.auditBy || '________' }}</span>
        </div>
      </div>
      <el-button type="primary" style="margin-top:14px" @click="printVoucher">打印（另存 PDF）</el-button>
      <template v-if="detail.status === 'DRAFT' && detail.sourceType === 'MANUAL'">
        <el-button type="success" style="margin-top:14px" @click="doAudit(detail.id)">审核通过</el-button>
      </template>
      <template v-if="detail.status === 'AUDITED'">
        <el-button type="warning" style="margin-top:14px" @click="doPost(detail.id)">过账</el-button>
      </template>
    </template>
  </el-drawer>
</template>

<script setup>
/**
 * 凭证详情/打印抽屉（跨页共用，design D2：状态放 useGl.js 单例）。
 * 供凭证录入页与凭证审核页「明细」渲染，审核/过账动作与接口不变。
 */
import { useGl } from '../useGl'

const { detailVisible, detail, printVoucher, doAudit, doPost } = useGl()
</script>

<style scoped>
@media print {
  .no-print, .el-drawer__header, .el-drawer__footer { display: none !important; }
  .print-area { padding: 20px; }
  body * { visibility: hidden; }
  .print-area, .print-area * { visibility: visible; }
  .print-area { position: absolute; left: 0; top: 0; width: 100%; }
}
</style>
