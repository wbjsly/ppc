<template>
  <div class="bg-page">
    <!-- ================= 16.5.2 双人审批 ================= -->
    <div class="toolbar">
      <el-button type="primary" @click="loadPending">刷新待办</el-button>
      <el-tag type="info">双人会签：值班经理 + 安全管理员，缺一不可；30 分钟未完成自动升级运维负责人</el-tag>
    </div>
    <el-table :data="pending" v-loading="pendLoading" border stripe>
      <el-table-column prop="targetUsername" label="被授权人" width="120" />
      <el-table-column label="模板" min-width="140">
        <template #default="{ row }">{{ row.templateCode }}</template>
      </el-table-column>
      <el-table-column prop="durationHours" label="时长(h)" width="80" align="center" />
      <el-table-column prop="reason" label="处置说明" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">审批</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 详情/签署对话框（跨页共用） -->
    <BgDetailDialog />
  </div>
</template>

<script setup>
/**
 * 16.5.2 双人审批（拆分自原 Break-Glass 操作台）：双人会签待办队列。
 * 待办列表与详情签署状态见 useBreakGlass.js。
 */
import { onMounted } from 'vue'
import { useBreakGlass } from './useBreakGlass'
import BgDetailDialog from './_parts/BgDetailDialog.vue'

const { pending, pendLoading, loadPending, openDetail } = useBreakGlass()

onMounted(loadPending)
</script>

<style scoped>
.bg-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
</style>
