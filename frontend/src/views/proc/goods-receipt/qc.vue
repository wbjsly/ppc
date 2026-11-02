<template>
  <div class="page">
    <div class="card-header">
      <span style="font-weight: bold; font-size: 16px;">{{ title }}</span>
      <span class="tip">2.4 收货管理 · FR-4.2-4-1 · C-4.2-04 容差 0.5% · BR-4.2-21/22/28/49</span>
    </div>

    <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
      title="待检看板（2.5 已接入）：数据源为检验批，时限自 GR 登记提交起算（偏差 D1），A/B/C = 24/48/72h"
      description="人工兜底放行已移除（qc-hold-area REMOVED）：检验录入/判定/放行在「来料检验」页完成；未放行的批次过账被 C-4.12-06 阻断。" />
    <el-table :data="qcList" size="small" border v-loading="loading">
      <el-table-column prop="lotNo" label="检验批号" width="170">
        <template #default="{ row }"><b>{{ row.lotNo }}</b></template>
      </el-table-column>
      <el-table-column prop="itemCode" label="物料" width="140" />
      <el-table-column prop="itemName" label="名称" min-width="130" />
      <el-table-column prop="supplierName" label="供应商" width="130" show-overflow-tooltip />
      <el-table-column prop="lotQty" label="批量" width="90" align="right" />
      <el-table-column label="风险等级" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="{ A: 'danger', B: 'warning', C: 'info' }[row.riskGrade] || 'info'" size="small">
            {{ row.riskGrade }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="检验状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'BLOCKED' ? 'danger' : 'primary'">
            {{ { BLOCKED: '无标准', PENDING: '待检', INPUTTING: '录入中',
                 REVIEWING: '复核', FROZEN: '冻结' }[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dueTime" label="截止时间" width="165" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.overdue" type="danger" size="small">超时</el-tag>
          <el-tag v-else type="success" size="small">进行中</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default>
          <el-button link type="primary" @click="$router.push('/m/2.5.1')">去检验</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!qcList.length" description="暂无在途检验批" :image-size="80" />
  </div>
</template>

<script setup>
/**
 * 2.4.2 待检管理（拆分自原收货管理操作台）：在途检验批看板，跳转来料检验页处置。
 * 共享状态与角色见 useGoodsReceipt.js。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getQcHoldApi } from '@/api/proc/goods-receipt'
import { useGoodsReceipt } from './useGoodsReceipt'

const route = useRoute()
const title = computed(() => route.meta.title || '待检管理')
const { loading } = useGoodsReceipt()

const qcList = ref([])
async function loadQc() {
  const { data } = await getQcHoldApi({ current: 1, size: 100 })
  qcList.value = data.records || []
}

onMounted(loadQc)
</script>

<style scoped>
.page { padding: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.tip { color: #909399; font-size: 12px; }
</style>
