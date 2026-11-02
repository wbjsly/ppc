<template>
  <div>
    <PdaShell :title="'移动报工 · ' + tabTitle" :scan="false" @sync="doSync">
      <el-table :data="orders" size="small" v-loading="loading">
        <el-table-column prop="moNo" label="工单" width="130" />
        <el-table-column prop="productName" label="产品" show-overflow-tooltip />
        <el-table-column prop="planQty" label="计划" width="70" />
        <el-table-column prop="opSeq" label="工序" width="55" />
        <el-table-column label="首件" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="faType(row.firstArticleStatus)">
              {{ faText(row.firstArticleStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="selectOrder(row)">开始领料</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-alert v-if="escalated > 0" type="warning" :closable="false" style="margin-top:8px"
                :title="`已升级 ${escalated} 个离线超 72h 终端待办`" />
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.1.1 工单接收（拆分自原移动报工操作台）：工单列表 → 开始领料。
 * 工单选中态（current/faState/idemKey）与首件标签辅助见 useReporting.js。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import PdaShell from '../_pda/PdaShell.vue'
import { myOrdersApi, scanPushEscalationsApi } from '@/api/iot/iot'
import { useReporting } from './useReporting'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '移动报工')

const { loading, current, faState, idemKey, faType, faText, doSync } = useReporting()

const orders = ref([])
const escalated = ref(0)

async function loadOrders() {
  loading.value = true
  try {
    const r = await myOrdersApi()
    orders.value = r.data || []
  } finally {
    loading.value = false
  }
}

function selectOrder(row) {
  current.value = row
  faState.value = row.firstArticleStatus
  idemKey.value = `${row.moNo}-${row.opSeq}-${Date.now()}-PDA`
}

onMounted(async () => {
  await loadOrders()
  // 72h 离线升级扫描（进入页面触发一次）
  try {
    const r = await scanPushEscalationsApi()
    escalated.value = r.data?.escalated || 0
  } catch (e) { /* 静默 */ }
})
</script>
