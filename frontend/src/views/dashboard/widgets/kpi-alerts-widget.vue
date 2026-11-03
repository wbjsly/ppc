<template>
  <WidgetShell title="KPI 预警" :loading="loading" :error="error" :badge="rows.length || 0" @refresh="load">
    <div v-if="rows.length === 0 && !error" class="wk-empty">当前无 ACTIVE 预警</div>
    <div v-for="a in rows" :key="a.id" class="wk-row">
      <div class="wk-row-main">
        <div class="wk-row-title">{{ a.kpiName || a.kpiKey }}</div>
        <div class="wk-row-sub">
          <span>连续低于目标 {{ a.daysBreached ?? '-' }} 天</span>
          <span v-if="a.firstBreachDate" class="wk-none">自 {{ String(a.firstBreachDate).slice(5) }}</span>
        </div>
      </div>
      <el-tag size="small" :type="a.status === 'ACTIVE' ? 'danger' : 'success'">
        {{ a.status === 'ACTIVE' ? '预警中' : '已恢复' }}
      </el-tag>
    </div>
  </WidgetShell>
</template>

<script setup>
/**
 * KPI 预警（add-configurable-workbench，段4 指标件）：GET /bi/kpi/alerts（默认 ACTIVE）。
 */
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import WidgetShell from '../_parts/WidgetShell.vue'

const loading = ref(false)
const error = ref('')
const rows = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await request({ url: '/bi/kpi/alerts', method: 'get', silent: true })
    rows.value = (res.data || []).slice(0, 6)
  } catch (e) {
    rows.value = []
    error.value = e?.response?.status === 403 ? '无权限查看预警'
      : e?.response ? `加载失败（${e.response.status}）` : (e.message || '网络异常')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.wk-empty { color: var(--el-text-color-secondary); font-size: 13px; text-align: center; padding: 18px 0; }
.wk-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 0; border-bottom: 1px dashed var(--el-border-color-lighter); }
.wk-row:last-child { border-bottom: none; }
.wk-row-main { min-width: 0; }
.wk-row-title { font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wk-row-sub { margin-top: 2px; font-size: 11px; color: var(--el-text-color-secondary); display: flex; gap: 8px; }
.wk-none { color: var(--el-text-color-placeholder); }
</style>
