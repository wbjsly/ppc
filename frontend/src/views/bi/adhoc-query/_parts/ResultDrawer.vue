<template>
  <!-- 结果抽屉（14.6.1 即席查询「我的任务」/ 14.6.3 临时报表「打开」两页共用） -->
  <el-drawer v-model="visible" :title="title" size="60%">
    <el-table :data="rows" size="small" border max-height="60vh">
      <el-table-column v-for="h in headers" :key="h" :prop="h" :label="h" />
    </el-table>
  </el-drawer>
</template>

<script setup>
/**
 * 查询结果抽屉（跨页共用，design D2：状态放本组件，两页通过 open() 打开）。
 * 行数据既可能是对象数组也可能是二维数组，按 headers 归一为对象行。
 */
import { ref } from 'vue'

const visible = ref(false)
const title = ref('')
const headers = ref([])
const rows = ref([])

function open({ title: t = '', headers: hs = [], rows: rs = [] } = {}) {
  title.value = t
  headers.value = hs
  rows.value = (rs || []).map(r => {
    if (typeof r === 'object' && !Array.isArray(r)) return r
    const o = {}; hs.forEach((h, i) => { o[h] = r?.[i] }); return o
  })
  visible.value = true
}

defineExpose({ open })
</script>
