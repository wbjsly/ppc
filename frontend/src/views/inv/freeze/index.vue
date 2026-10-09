<template>
  <div>
    <el-page-header content="冻结库存总览（4.3.2）— 全类型查询与统一入口" style="margin-bottom: 8px" />
    <FreezePanel />
  </div>
</template>

<script setup>
// 4.3.2 冻结库存总览（add-stock-snapshot-three-state 保留菜单）：
// 混合类型查询 + 统一入口；组件与 4.9.1/4.9.2 同构（type 分流见 FreezePanel）
import FreezePanel from './FreezePanel.vue'
</script>
