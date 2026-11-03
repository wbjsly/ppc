<template>
  <WidgetShell title="会计期间" :loading="loading" :error="error" @refresh="load">
    <div v-if="data" class="wk-period">
      <div class="wk-period-no">{{ fmtPeriod(data.period) }}</div>
      <div class="wk-period-meta">
        <el-tag size="small" :type="data.status === 'OPEN' ? 'success' : data.status === 'CLOSED' ? 'danger' : 'warning'">
          {{ statusLabel(data.status) }}
        </el-tag>
        <span v-if="data.closeAt" class="wk-none">关账 {{ String(data.closeAt).replace('T', ' ').slice(0, 16) }}</span>
      </div>
      <div class="wk-hint">{{ data.status === 'OPEN' ? '凭证可录入与过账' : '期间已关账，录入受阻（BR-5.7-12）' }}</div>
    </div>
    <div v-else-if="!error" class="wk-empty">当前期间未配置/未生成</div>
  </WidgetShell>
</template>

<script setup>
/**
 * 会计期间状态（add-configurable-workbench，段4 指标件）：GET /fin/periods/current。
 */
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import WidgetShell from '../_parts/WidgetShell.vue'

const loading = ref(false)
const error = ref('')
const data = ref(null)

const STATUS = { OPEN: '已开账', CLOSED: '已关账', CLOSING: '关账中' }
function statusLabel(s) { return STATUS[s] || s || '-' }
function fmtPeriod(p) {
  if (!p) return '-'
  const s = String(p)
  return s.length === 6 ? `${s.slice(0, 4)} 年 ${s.slice(4)} 月` : s
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await request({ url: '/fin/periods/current', method: 'get', silent: true })
    data.value = res.data || null
  } catch (e) {
    data.value = null
    error.value = e?.response?.status === 403 ? '无权限查看期间'
      : e?.response ? `加载失败（${e.response.status}）` : (e.message || '网络异常')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.wk-period { text-align: center; padding: 6px 0; }
.wk-period-no { font-size: 26px; font-weight: 700; color: var(--el-color-primary); margin-bottom: 6px; }
.wk-period-meta { display: flex; align-items: center; justify-content: center; gap: 8px; }
.wk-hint { margin-top: 8px; font-size: 11px; color: var(--el-text-color-secondary); }
.wk-empty { color: var(--el-text-color-secondary); font-size: 13px; text-align: center; padding: 18px 0; }
.wk-none { font-size: 11px; color: var(--el-text-color-placeholder); }
</style>
