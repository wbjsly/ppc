<template>
  <WidgetShell title="我的待办" :loading="loading" :error="error" :badge="badge" @refresh="load">
    <div v-if="rows.length === 0" class="wk-empty">暂无待办</div>
    <div v-for="t in rows" :key="t.id" class="wk-row">
      <div class="wk-row-main">
        <div class="wk-row-title" :title="t.title">{{ t.title }}</div>
        <div class="wk-row-sub">
          <el-tag size="small" effect="plain">{{ typeLabel(t.todoType) }}</el-tag>
          <span v-if="t.dueAt" class="wk-due">截止 {{ fmt(t.dueAt) }}</span>
        </div>
      </div>
      <el-tag size="small" :type="statusType(t.status)">{{ statusLabel(t.status) }}</el-tag>
    </div>
  </WidgetShell>
</template>

<script setup>
/**
 * 我的待办（add-configurable-workbench，spec personal-workbench，段3 核心件）：
 * GET /iot/todos 聚合待办（本域 + 审批流，超时倒序）按当前用户名过滤；
 * silent 静默请求——失败只在卡片内呈现（单卡失败不弹全局、不传播）。
 */
import { ref, computed, onMounted } from 'vue'
import request from '@/utils/request'
import { useUserStore } from '@/store/user'
import WidgetShell from '../_parts/WidgetShell.vue'

const userStore = useUserStore()
const loading = ref(false)
const error = ref('')
const rows = ref([])

const OPEN_STATUS = ['OPEN', 'PENDING', 'ESCALATED']
const badge = computed(() => rows.value.filter(t => OPEN_STATUS.includes(String(t.status || '').toUpperCase())).length)

const TYPE = { REPORT: '报工', APPROVAL: '审批', INSPECT: '巡检', ABNORMAL: '异常', OFFLINE: '离线' }
const STATUS = { OPEN: '待处理', PENDING: '待处理', ESCALATED: '已升级', HANDLED: '已处理', CANCELLED: '已取消' }

function typeLabel(t) { return TYPE[t] || t || '待办' }
function statusLabel(s) { return STATUS[String(s || '').toUpperCase()] || s || '-' }
function statusType(s) {
  const k = String(s || '').toUpperCase()
  if (k === 'ESCALATED') return 'danger'
  if (k === 'HANDLED' || k === 'CANCELLED') return 'info'
  return 'warning'
}
function fmt(s) { return String(s).replace('T', ' ').slice(0, 16) }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const username = userStore.userInfo?.username
    const res = await request({ url: '/iot/todos', method: 'get', params: { assignee: username }, silent: true })
    rows.value = (res.data || []).slice(0, 8)
  } catch (e) {
    rows.value = []
    error.value = e?.response?.status === 403 ? '无权限查看待办'
      : e?.response ? `加载失败（${e.response.status}）` : '网络异常'
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
.wk-row-title { font-size: 13px; color: var(--el-text-color-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wk-row-sub { display: flex; align-items: center; gap: 6px; margin-top: 2px; }
.wk-due { font-size: 11px; color: var(--el-text-color-secondary); }
</style>
