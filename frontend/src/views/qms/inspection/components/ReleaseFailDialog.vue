<template>
  <el-dialog :model-value="modelValue" title="放行校验未通过" width="520px"
             @update:model-value="$emit('update:modelValue', $event)">
    <el-alert type="error" :closable="false" style="margin-bottom: 10px;"
              :title="`批次 ${lotNo || ''} 前置校验存在未通过项（C-4.12-06）`"
              description="补全下列未通过项后可重试放行；批次状态保持不变。" />
    <ul class="fail-list">
      <li v-for="(f, i) in items" :key="i">{{ f }}</li>
    </ul>
    <template #footer>
      <el-button type="primary" @click="$emit('update:modelValue', false)">知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: Boolean,
  message: { type: String, default: '' },
  lotNo: { type: String, default: '' }
})
defineEmits(['update:modelValue'])

/** 后端 422 文案：「放行校验未通过：A；B；C」→ 逐条清单 */
const items = computed(() => {
  const m = (props.message || '').replace(/^.*?放行校验未通过[：:]\s*/, '')
  return m ? m.split(/[；;]/).map(s => s.trim()).filter(Boolean) : []
})
</script>

<style scoped>
.fail-list { margin: 0; padding-left: 20px; font-size: 13px; color: #f56c6c; line-height: 1.9; }
</style>
