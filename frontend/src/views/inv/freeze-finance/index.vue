<template>
  <div>
    <FreezePanel freeze-type="FINANCE" />
  </div>
</template>

<script setup>
// 4.9.2 财务冻结：复用 FreezePanel 按 freezeType 分流（仅财务台账、仅财务角色可发起）
import FreezePanel from '../freeze/FreezePanel.vue'
</script>
