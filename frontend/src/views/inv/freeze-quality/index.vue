<template>
  <div>
    <FreezePanel freeze-type="QUALITY" />
  </div>
</template>

<script setup>
// 4.9.1 质量冻结：复用 FreezePanel 按 freezeType 分流（仅质量台账、仅质量角色可发起）
import FreezePanel from '../freeze/FreezePanel.vue'
</script>
