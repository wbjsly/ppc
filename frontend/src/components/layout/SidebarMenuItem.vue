<template>
  <el-sub-menu v-if="item.children && item.children.length" :index="item.id">
    <template #title>
      <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
      <span>{{ item.title }}</span>
    </template>
    <SidebarMenuItem v-for="child in item.children" :key="child.id" :item="child" />
  </el-sub-menu>
  <el-menu-item v-else :index="item.path || item.id" :disabled="!item.path">
    <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
    <span>{{ item.title }}</span>
  </el-menu-item>
</template>

<script setup>
defineOptions({ name: 'SidebarMenuItem' })
defineProps({ item: { type: Object, required: true } })
</script>
