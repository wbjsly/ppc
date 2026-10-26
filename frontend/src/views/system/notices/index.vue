<template>
  <div>
    <el-page-header content="通知中心 — 站内通知统一查看（按角色聚合，未读优先）" style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-radio-group v-model="view" size="small" @change="load">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="unread">未读</el-radio-button>
        <el-radio-button value="read">已读</el-radio-button>
      </el-radio-group>
      <el-button size="small" @click="load">刷新</el-button>
      <span class="tip">未读 {{ unread }} 条；点击条目标记已读</span>
    </div>

    <el-table :data="filtered" v-loading="loading" border stripe size="small"
              @row-click="readRow" style="cursor: pointer">
      <el-table-column label="状态" width="70" align="center">
        <template #default="{ row }">
          <el-tag :type="row.readFlag === '0' ? 'danger' : 'info'" size="small">
            {{ row.readFlag === '0' ? '未读' : '已读' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
      <el-table-column prop="content" label="内容" min-width="320" show-overflow-tooltip />
      <el-table-column prop="bizType" label="类型" width="170">
        <template #default="{ row }">
          <el-tag size="small" type="warning" effect="plain">{{ bizText(row.bizType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="150">
        <template #default="{ row }">
          {{ (row.createDate || '').replace('T', ' ').slice(0, 16) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.readFlag === '0'" link type="primary" size="small"
                     @click.stop="readRow(row)">标记已读</el-button>
          <span v-else style="color: #c0c4cc; font-size: 12px">—</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && filtered.length === 0" description="暂无通知" :image-size="60" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { getNoticesApi, markNoticeReadApi } from '@/api/notice'

const loading = ref(false)
const view = ref('all')
const notices = ref([])
const unread = computed(() => notices.value.filter(n => n.readFlag === '0').length)
// 服务端已按未读优先+时间倒序（上限 100）；前端按视图过滤
const filtered = computed(() => {
  if (view.value === 'unread') return notices.value.filter(n => n.readFlag === '0')
  if (view.value === 'read') return notices.value.filter(n => n.readFlag === '1')
  return notices.value
})

const bizText = t => ({
  MO_SHORTAGE: '工单缺料预警',
  MO_KITTING_RESTORED: '齐套恢复可派工',
  OPP_LARGE_AMOUNT: '大额商机提醒'
}[t] || t || '系统通知')

async function load() {
  loading.value = true
  try {
    const res = await getNoticesApi()
    notices.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function readRow(row) {
  if (row.readFlag !== '0') return
  try {
    await markNoticeReadApi(row.id)
    row.readFlag = '1'
    row.readAt = new Date().toISOString()
    ElMessage.success('已标记已读')
  } catch { /* 弹错单点在 request.js */ }
}

onMounted(load)
onActivated(load) // 列表页回刷约定（01 文档 3.5）
</script>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 8px; }
.tip { color: #909399; font-size: 12px; }
</style>
