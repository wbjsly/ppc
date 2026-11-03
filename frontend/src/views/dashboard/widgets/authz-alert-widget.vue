<template>
  <WidgetShell title="越权安全告警" :loading="loading" :error="error" :badge="rows.length || 0" @refresh="load">
    <div v-if="rows.length === 0 && !error" class="wk-empty">无 ACTIVE 降权事件</div>
    <div v-for="i in rows" :key="i.id" class="wk-row">
      <div class="wk-row-main">
        <div class="wk-row-title">{{ i.targetUsername || i.targetUserId || i.targetUserId }}</div>
        <div class="wk-row-sub">
          <span>{{ i.hitCount }}/{{ i.threshold }} 次 403</span>
          <span v-if="i.triggeredAt" class="wk-none">{{ fmt(i.triggeredAt) }}</span>
        </div>
      </div>
      <el-tag size="small" type="danger">降权中</el-tag>
    </div>
  </WidgetShell>
</template>

<script setup>
/**
 * 越权安全告警（add-configurable-workbench，段5 告警件）：GET /system/authz-incidents。
 * perm=['ROLE_ADMIN'] 本地预过滤（无权限用户不渲染、不发请求——403 主闸）；
 * 数据接口另由 URL hasRole(ADMIN) + AuthzDenyFilter 敏感前缀降权双兜底。
 */
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import WidgetShell from '../_parts/WidgetShell.vue'

const loading = ref(false)
const error = ref('')
const rows = ref([])

function fmt(s) { return String(s || '').replace('T', ' ').slice(5, 16) }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await request({
      url: '/system/authz-incidents', method: 'get',
      params: { status: 'ACTIVE', size: 6 }, silent: true,
    })
    rows.value = res.data?.rows || []
  } catch (e) {
    rows.value = []
    error.value = e?.response?.status === 403 ? '无权限（需 ROLE_ADMIN）'
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
