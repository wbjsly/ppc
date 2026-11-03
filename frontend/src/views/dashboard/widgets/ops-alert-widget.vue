<template>
  <WidgetShell title="运维告警" :loading="loading" :error="error" :badge="rows.length || 0" @refresh="load">
    <div v-if="rows.length === 0 && !error" class="wk-empty">当前无告警</div>
    <div v-for="a in rows" :key="a.id" class="wk-row">
      <div class="wk-row-main">
        <div class="wk-row-title">{{ a.title || a.alertNo }}</div>
        <div class="wk-row-sub">
          <span>{{ a.alertNo }}</span>
          <span v-if="a.createDate" class="wk-none">{{ fmt(a.createDate) }}</span>
        </div>
      </div>
      <el-tag size="small" :type="levelType(a.level)">{{ a.level || '-' }}·{{ statusLabel(a.status) }}</el-tag>
    </div>
  </WidgetShell>
</template>

<script setup>
/**
 * 运维告警（add-configurable-workbench，段5 告警件）：GET /ops/alerts。
 */
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import WidgetShell from '../_parts/WidgetShell.vue'

const loading = ref(false)
const error = ref('')
const rows = ref([])

const LEVEL = { P1: 'danger', P2: 'warning', P3: 'info' }
const STATUS = { OPEN: '待响应', ESCALATED: '已升级', ACKED: '已认领', CLOSED: '已关闭' }
function levelType(l) { return LEVEL[l] || 'warning' }
function statusLabel(s) { return STATUS[s] || s || '-' }
function fmt(s) { return String(s || '').replace('T', ' ').slice(5, 16) }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await request({ url: '/ops/alerts', method: 'get', params: { size: 6 }, silent: true })
    rows.value = (res.data || []).slice(0, 6)
  } catch (e) {
    rows.value = []
    error.value = e?.response?.status === 403 ? '无权限查看告警'
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
