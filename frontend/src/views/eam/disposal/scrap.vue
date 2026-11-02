<template>
  <div class="page">
    <el-form inline>
      <el-form-item label="状态">
        <el-select v-model="scrapQuery.status" clearable placeholder="全部" style="width:160px">
          <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-button type="primary" @click="loadScrap">查询</el-button>
      <el-button type="success" @click="openCreate('SCRAP')">新建报废单</el-button>
    </el-form>
    <el-table :data="scrapRows" border>
      <el-table-column prop="dspNo" label="处置单号" width="170" />
      <el-table-column prop="appStatus" label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="statusType(row.appStatus)">{{ row.appStatus }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="totalCost" label="原值合计" width="120" />
      <el-table-column prop="totalNbv" label="NBV 合计" width="120" />
      <el-table-column prop="gainLoss" label="处置损益" width="120" />
      <el-table-column label="操作" width="260">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row.id)">明细</el-button>
          <el-button v-if="['DRAFT','REJECTED'].includes(row.appStatus)" size="small"
                     type="primary" @click="doSubmit(row.id)">提交审批</el-button>
          <el-button v-if="row.appStatus === 'APPROVED'" size="small" type="warning"
                     @click="openExec(row.id, 'SCRAP')">执行确认</el-button>
        </template>
      </el-table-column>
    </el-table>

    <CreateDialog />
    <DetailDialog />
    <ExecDialog />
  </div>
</template>

<script setup>
/**
 * 7.6.1 报废申请（拆分自原资产处置操作台）：报废单列表、提交审批与执行确认。
 * 共享状态与对话框见 useDisposal.js 与 _parts/。
 */
import { onMounted } from 'vue'
import { useDisposal } from './useDisposal'
import CreateDialog from './_parts/CreateDialog.vue'
import DetailDialog from './_parts/DetailDialog.vue'
import ExecDialog from './_parts/ExecDialog.vue'

const { STATUSES, statusType, scrapQuery, scrapRows, loadScrap,
  openCreate, doSubmit, openExec, openDetail } = useDisposal()

onMounted(() => loadScrap())
</script>

<style scoped>
.page { padding: 8px; }
</style>
