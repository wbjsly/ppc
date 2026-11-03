<template>
  <WidgetShell title="站内通知" :loading="loading" :error="error" :badge="unread" @refresh="load">
    <div v-if="rows.length === 0" class="wk-empty">暂无通知</div>
    <div v-for="n in rows" :key="n.id" class="wk-row" :class="{ unread: n.readFlag !== '1' }">
      <div class="wk-row-main">
        <div class="wk-row-title" :title="n.title">
          <span v-if="n.readFlag !== '1'" class="wk-dot" />{{ n.title }}
        </div>
        <div class="wk-row-sub">
          <span class="wk-time">{{ fmt(n.createDate) }}</span>
        </div>
      </div>
    </div>
  </WidgetShell>
</template>

<script setup>
/**
 * 站内通知（add-configurable-workbench，段3 核心件）：
 * 未读数徽标 + 最新 6 条（GET /notices 未读在前时间倒序上限100）。
 * 单卡静默请求（silent），错误只落卡片。
 */
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import WidgetShell from '../_parts/WidgetShell.vue'

const loading = ref(false)
const error = ref('')
const rows = ref([])
const unread = ref(0)

function fmt(s) { return String(s || '').replace('T', ' ').slice(0, 16) }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [listRes, unreadRes] = await Promise.all([
      request({ url: '/notices', method: 'get', silent: true }),
      request({ url: '/notices/unread-count', method: 'get', silent: true }),
    ])
    rows.value = (listRes.data || []).slice(0, 6)
    unread.value = unreadRes.data || 0
  } catch (e) {
    rows.value = []
    error.value = e?.response?.status === 403 ? '无权限查看通知'
      : e?.response ? `加载失败（${e.response.status}）` : '网络异常'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.wk-empty { color: var(--el-text-color-secondary); font-size: 13px; text-align: center; padding: 18px 0; }
.wk-row { padding: 6px 0; border-bottom: 1px dashed var(--el-border-color-lighter); }
.wk-row:last-child { border-bottom: none; }
.wk-row-title { font-size: 13px; color: var(--el-text-color-regular); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wk-row.unread .wk-row-title { color: var(--el-text-color-primary); font-weight: 600; }
.wk-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--el-color-danger); margin-right: 5px; vertical-align: middle; }
.wk-row-sub { margin-top: 2px; }
.wk-time { font-size: 11px; color: var(--el-text-color-secondary); }
</style>
