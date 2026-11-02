<template>
  <div>
    <PdaShell :title="'移动报工 · ' + tabTitle" :scan="false" @sync="doSync">
      <el-table :data="reviewList" size="small" v-loading="loading">
        <el-table-column prop="TITLE" label="待审" show-overflow-tooltip />
        <el-table-column prop="ASSIGNEE" label="处理人" width="110" />
        <el-table-column prop="DUE_AT" label="超时计时" width="150" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" type="success" link @click="review(row, true)">通过</el-button>
            <el-button size="small" type="danger" link @click="review(row, false)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.1.4 报工审核（拆分自原移动报工操作台）：车间主任超产/异常待审通过驳回。
 * 加载态与同步提示见 useReporting.js。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import { reviewReportApi } from '@/api/iot/iot'
import request from '@/utils/request'
import { useReporting } from './useReporting'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '移动报工')

const { loading, doSync } = useReporting()

const reviewList = ref([])

async function loadReview() {
  loading.value = true
  try {
    const r = await request.get('/iot/todos', { params: { todoType: 'REPORT', status: 'PENDING' } })
    reviewList.value = r.data || []
  } catch (e) {
    reviewList.value = []
  } finally {
    loading.value = false
  }
}

async function review(row, pass) {
  try {
    await reviewReportApi(row.REF_ID || row.refId, { pass, opinion: pass ? '同意' : '驳回' })
    ElMessage.success('已处理')
    loadReview()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

onMounted(() => {
  loadReview()
})
</script>
