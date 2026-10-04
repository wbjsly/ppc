<template>
  <div class="placeholder-page">
    <el-card v-loading="loading">
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">{{ title }}</span>
        </div>
      </template>
      <el-result icon="info" title="功能建设中" :sub-title="`菜单 ${code}「${title}」对应页面尚未开发，将按 08 菜单清单逐域推进。`">
        <template #extra>
          <el-button type="primary" @click="$router.push('/dashboard')">返回工作台</el-button>
        </template>
      </el-result>
      <el-descriptions v-if="menu" :column="2" border>
        <el-descriptions-item label="菜单编号">{{ menu.menuCode || '-' }}</el-descriptions-item>
        <el-descriptions-item label="菜单名称">{{ menu.title }}</el-descriptions-item>
        <el-descriptions-item label="路由路径">{{ menu.path || '-' }}</el-descriptions-item>
        <el-descriptions-item label="可见角色">{{ menu.perm || '所有登录用户' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onActivated } from 'vue'
import { useRoute } from 'vue-router'
import { getMenuListApi } from '@/api/system/menu'

const route = useRoute()
const loading = ref(false)
const allMenus = ref([])

const menu = computed(() => allMenus.value.find(m => m.path === route.path))
const title = computed(() => menu.value?.title || '功能建设中')
const code = computed(() => menu.value?.menuCode || '-')

async function loadMenus() {
  loading.value = true
  try {
    const res = await getMenuListApi()
    allMenus.value = res.data || []
  } catch {
    // 弹错由拦截器统一处理
  } finally {
    loading.value = false
  }
}

watch(() => route.path, () => {
  if (!allMenus.value.length) loadMenus()
})

onActivated(() => {
  if (!allMenus.value.length) loadMenus()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.placeholder-page :deep(.el-descriptions) {
  margin-top: 16px;
}
</style>
