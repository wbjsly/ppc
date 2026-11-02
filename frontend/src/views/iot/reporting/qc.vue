<template>
  <div>
    <PdaShell :title="'移动报工 · ' + tabTitle" :scan="false" @sync="doSync">
      <el-table :data="faQueue" size="small" v-loading="loading">
        <el-table-column prop="moNo" label="工单" width="130" />
        <el-table-column prop="opSeq" label="工序" width="55" />
        <el-table-column prop="ctqJson" label="CTQ 数据" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="faType(row.status)">{{ faText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="success" link @click="confirmFa(row, true)">通过</el-button>
              <el-button size="small" type="danger" link @click="confirmFa(row, false)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.1.3 质检确认（拆分自原移动报工操作台）：IPQC 首件队列通过/驳回。
 * 加载态与首件标签辅助见 useReporting.js。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import { confirmFirstArticleApi } from '@/api/iot/iot'
import request from '@/utils/request'
import { useReporting } from './useReporting'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '移动报工')

const { loading, faType, faText, doSync } = useReporting()

const faQueue = ref([])

async function loadFaQueue() {
  // 质检确认队列：取 PENDING 首件（经待办/直接查询近似——列表页由后端首件接口提供）
  loading.value = true
  try {
    const r = await request.get('/iot/reporting/my-orders')
    faQueue.value = (r.data || [])
      .filter(o => o.firstArticleStatus === 'PENDING')
      .map(o => ({ moNo: o.moNo, opSeq: o.opSeq, ctqJson: '（见报工端提交）', id: o.dispatchId, status: 'PENDING', dispatchId: o.dispatchId }))
  } finally {
    loading.value = false
  }
}

async function confirmFa(row, pass) {
  try {
    await confirmFirstArticleApi(row.id, { pass, remark: pass ? 'IPQC 确认通过' : '首件不合格' })
    ElMessage.success(pass ? '已通过，解锁批量报工' : '已驳回')
    loadFaQueue()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

onMounted(() => {
  loadFaQueue()
})
</script>
