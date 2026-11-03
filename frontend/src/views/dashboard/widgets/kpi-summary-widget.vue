<template>
  <WidgetShell title="KPI 摘要" :loading="loading" :error="error" @refresh="load">
    <div v-if="rows.length === 0 && !error" class="wk-empty">暂无 KPI 数据</div>
    <div v-for="k in rows" :key="k.kpiKey" class="wk-row">
      <div class="wk-row-main">
        <div class="wk-row-title">{{ k.name || k.kpiKey }}</div>
        <div class="wk-row-sub">
          <template v-if="k.actual !== null && k.target !== null">
            <span class="wk-num">{{ fmtNum(k.actual) }}</span>
            <span class="wk-target">/{{ fmtNum(k.target) }}</span>
          </template>
          <span v-else class="wk-none">无快照数据</span>
        </div>
      </div>
      <el-tag v-if="ratioOf(k) !== null" size="small" :type="levelOf(k)">{{ (ratioOf(k) * 100).toFixed(1) }}%</el-tag>
      <el-tag v-else size="small" type="info">-</el-tag>
    </div>
  </WidgetShell>
</template>

<script setup>
/**
 * KPI 摘要（add-configurable-workbench，段4 指标件）：GET /bi/kpi/summary。
 * 达成率三色按数据内 thresholdWarn/thresholdCritical 本地判级（C-4.10-01 口径在后端）。
 */
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import WidgetShell from '../_parts/WidgetShell.vue'

const loading = ref(false)
const error = ref('')
const rows = ref([])

function ratioOf(k) { return k.ratio === null || k.ratio === undefined ? null : Number(k.ratio) }
function levelOf(k) {
  const r = ratioOf(k)
  if (r === null) return 'info'
  if (r < Number(k.thresholdCritical ?? 0.5)) return 'danger'
  if (r < Number(k.thresholdWarn ?? 0.8)) return 'warning'
  return 'success'
}
function fmtNum(n) {
  if (n === null || n === undefined) return '-'
  const v = Number(n)
  return Math.abs(v) >= 10000 ? v.toLocaleString('zh-CN', { maximumFractionDigits: 1 }) : String(v)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await request({ url: '/bi/kpi/summary', method: 'get', silent: true })
    rows.value = (res.data || []).slice(0, 6)
  } catch (e) {
    rows.value = []
    error.value = e?.response?.status === 403 ? '无权限查看 KPI'
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
.wk-row-sub { margin-top: 2px; font-size: 11px; color: var(--el-text-color-secondary); }
.wk-num { color: var(--el-text-color-primary); font-weight: 600; }
.wk-none { color: var(--el-text-color-placeholder); }
</style>
