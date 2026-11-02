<template>
  <el-dialog v-model="detailVisible" title="处置单明细" width="900px">
    <template v-if="detail.order">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="单号">{{ detail.order.dspNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail.order.appStatus }}</el-descriptions-item>
        <el-descriptions-item label="方式">{{ detail.order.disposalType === 'SCRAP' ? '报废' : '出售' }}</el-descriptions-item>
        <el-descriptions-item label="原值合计">{{ detail.order.totalCost }}</el-descriptions-item>
        <el-descriptions-item label="NBV 合计">{{ detail.order.totalNbv }}</el-descriptions-item>
        <el-descriptions-item label="处置价">{{ detail.order.totalProceeds }}</el-descriptions-item>
        <el-descriptions-item label="回收合计">{{ detail.order.totalRecycle }}</el-descriptions-item>
        <el-descriptions-item label="处置损益">{{ detail.order.gainLoss }}</el-descriptions-item>
        <el-descriptions-item label="应收余额">{{ detail.order.receivableBal }}</el-descriptions-item>
        <el-descriptions-item label="评估残值">{{ detail.order.estResidual }}</el-descriptions-item>
        <el-descriptions-item label="复核说明" :span="2">{{ detail.order.reviewNote || '—' }}</el-descriptions-item>
        <el-descriptions-item label="执行凭证" :span="3">{{ detail.order.remark || '—' }}</el-descriptions-item>
        <el-descriptions-item label="结案报告" :span="3">{{ detail.order.reportSummary || '—' }}</el-descriptions-item>
      </el-descriptions>
      <el-divider content-position="left">处置明细（凭证锚 DISPOSAL_TRANSFER/PROCEEDS/RECYCLE/GAINLOSS）</el-divider>
      <el-table :data="detail.lines" border size="small">
        <el-table-column prop="cardNo" label="卡号" width="130" />
        <el-table-column prop="assetName" label="名称" />
        <el-table-column prop="origCost" label="原值" width="100" />
        <el-table-column prop="accumDepr" label="累计折旧" width="100" />
        <el-table-column prop="nbv" label="NBV" width="100" />
        <el-table-column prop="lineProceeds" label="处置价" width="100" />
      </el-table>
      <el-table v-if="detail.recycles && detail.recycles.length" :data="detail.recycles" border size="small" style="margin-top:8px">
        <el-table-column prop="itemName" label="回收物" width="160" />
        <el-table-column prop="itemCode" label="物料编码" width="140" />
        <el-table-column prop="qty" label="数量" width="80" />
        <el-table-column prop="estPrice" label="估价" width="100" />
        <el-table-column prop="grId" label="入库单" width="200" />
        <el-table-column prop="grStatus" label="入库状态" width="100" />
      </el-table>
    </template>
  </el-dialog>
</template>

<script setup>
/** 处置单明细对话框（拆分自原资产处置操作台，三页共用）。 */
import { useDisposal } from '../useDisposal'
const { detailVisible, detail } = useDisposal()
</script>
