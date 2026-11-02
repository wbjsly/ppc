<template>
  <div class="page">
    <el-form inline>
      <el-button type="primary" @click="loadPl">刷新</el-button>
    </el-form>
    <el-table :data="plRows" border>
      <el-table-column prop="dspNo" label="处置单号" width="170" />
      <el-table-column prop="disposalType" label="方式" width="80">
        <template #default="{ row }">{{ row.disposalType === 'SCRAP' ? '报废' : '出售' }}</template>
      </el-table-column>
      <el-table-column prop="appStatus" label="状态" width="130" />
      <el-table-column prop="totalNbv" label="NBV" width="110" />
      <el-table-column prop="gainLoss" label="处置损益" width="110" />
      <el-table-column prop="receivableBal" label="应收余额" width="110" />
      <el-table-column label="操作" width="300">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row.id)">凭证锚明细</el-button>
          <el-button v-if="['RECEIVABLE','EXECUTED'].includes(row.appStatus) && row.receivableBal > 0"
                     size="small" type="success" @click="openReceive(row.id)">收款登记</el-button>
          <el-button v-if="['EXECUTED','RECEIVABLE'].includes(row.appStatus)"
                     size="small" type="primary" @click="doSubmitReport(row.id)">结案报告</el-button>
          <el-button v-if="['EXECUTED','RECEIVABLE'].includes(row.appStatus)"
                     size="small" type="danger" @click="doReverse(row.id)">整单冲回</el-button>
        </template>
      </el-table-column>
    </el-table>

    <DetailDialog />
    <ReceiveDialog />
  </div>
</template>

<script setup>
/**
 * 7.6.3 损益处理（拆分自原资产处置操作台）：凭证锚明细、收款、结案报告与整单冲回。
 * 共享状态与对话框见 useDisposal.js 与 _parts/。
 */
import { onMounted } from 'vue'
import { useDisposal } from './useDisposal'
import DetailDialog from './_parts/DetailDialog.vue'
import ReceiveDialog from './_parts/ReceiveDialog.vue'

const { plRows, loadPl, openDetail, openReceive, doSubmitReport, doReverse } = useDisposal()

onMounted(() => loadPl())
</script>

<style scoped>
.page { padding: 8px; }
</style>
