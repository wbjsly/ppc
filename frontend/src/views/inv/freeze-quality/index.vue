<template>
  <div>
    <el-page-header content="质量冻结（4.9.1）— 质量管理员发起、质量经理审批" style="margin-bottom: 8px" />
    <FreezePanel freeze-type="QUALITY" />
  </div>
</template>

<script setup>
// 4.9.1 质量冻结：复用 FreezePanel 按 freezeType 分流（仅质量台账、仅质量角色可发起）
import FreezePanel from '../freeze/FreezePanel.vue'
</script>
