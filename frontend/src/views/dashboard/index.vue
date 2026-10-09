<template>
  <div class="dashboard-page">
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">欢迎回来</div>
          <div class="stat-value">{{ userStore.userInfo?.nickName || userStore.userInfo?.username || '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">可见菜单</div>
          <div class="stat-value">{{ menuStore.menuItems.length }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">当前角色</div>
          <div class="stat-value roles">
            <el-tag v-for="r in roles" :key="r" size="small">{{ r }}</el-tag>
            <span v-if="roles.length === 0">-</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-top: 16px;">
      <template #header>
        <div style="font-weight: bold; font-size: 16px;">快速开始</div>
      </template>
      <div class="quick-actions">
        <el-button type="primary" size="large" @click="$router.push('/system/menu')" v-if="isAdmin">
          菜单管理
        </el-button>
        <div class="quick-hint">
          本工程当前实现「系统管理 - 菜单管理」模块；左侧导航其余功能点按
          docs/design/08-erp-menu.md 清单占位，后续按域逐步开发。
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from '@/store/user'
import { useMenuStore } from '@/store/menu'

const userStore = useUserStore()
const menuStore = useMenuStore()

const roles = computed(() => userStore.userInfo?.roles || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
</script>

<style scoped>
.stat-card :deep(.el-card__body) {
  padding: 20px;
}
.stat-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}
.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #409eff;
}
.stat-value.roles {
  font-size: 14px;
  display: flex;
  gap: 8px;
  align-items: center;
}
.quick-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.quick-hint {
  font-size: 13px;
  color: #909399;
}
</style>
