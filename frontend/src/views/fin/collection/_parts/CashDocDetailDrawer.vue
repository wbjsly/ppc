<template>
  <!-- 收付款单详情抽屉（8.4.1 收款执行 / 8.4.2 付款执行 两页共用） -->
  <el-drawer v-model="detailVisible" :title="detailDoc?.docNo || '单据详情'" size="480px">
    <template v-if="detailDoc">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="方向">{{ detailDoc.direction === 'IN' ? '收款' : '付款' }}</el-descriptions-item>
        <el-descriptions-item label="往来对象">{{ detailDoc.partyName }}</el-descriptions-item>
        <el-descriptions-item label="金额">{{ detailDoc.amount }}</el-descriptions-item>
        <el-descriptions-item label="实收/付">{{ detailDoc.actualAmount }}</el-descriptions-item>
        <el-descriptions-item label="方式">{{ detailDoc.payMethod }}</el-descriptions-item>
        <el-descriptions-item label="日期">{{ detailDoc.payDate }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detailDoc.status }}</el-descriptions-item>
        <el-descriptions-item label="核销状态">{{ detailDoc.writeoffState }}</el-descriptions-item>
        <el-descriptions-item label="凭证 ID">{{ detailDoc.voucherId || '—' }}</el-descriptions-item>
        <el-descriptions-item v-if="detailDoc.rejectReason" label="驳回原因">
          {{ detailDoc.rejectReason }}
        </el-descriptions-item>
      </el-descriptions>
      <el-divider>核销明细</el-divider>
      <el-table :data="detailWriteoffs" border size="small">
        <el-table-column prop="woNo" label="核销单号" width="150" />
        <el-table-column prop="targetType" label="类型" width="60" />
        <el-table-column prop="targetNo" label="目标单号" min-width="130" show-overflow-tooltip />
        <el-table-column prop="amount" label="金额" width="90" align="right" />
        <el-table-column prop="writeoffMode" label="方式" width="80" />
      </el-table>
    </template>
  </el-drawer>
</template>

<script setup>
/**
 * 收付款单详情抽屉（跨页共用，design D2：状态放 useCollection.js 单例）。
 * 供收款执行页与付款执行页渲染，接口与展示不变。
 */
import { useCollection } from '../useCollection'

const { detailVisible, detailDoc, detailWriteoffs } = useCollection()
</script>
