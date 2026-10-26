<template>
  <el-tooltip v-if="frozen" :content="reason || '项目已超支冻结，新增支出动作已被拦截'" placement="top">
    <el-tag type="danger" effect="dark" size="small">已冻结</el-tag>
  </el-tooltip>
</template>

<script setup>
/**
 * 项目冻结徽标（add-cost-collection-freeze，BR-4.8-21）。
 * 用法：<ProjectFreezeBadge :project-id="xxx" />——挂载在预算/工时/成本页头，
 * 冻结时展示红色徽标与冻结原因。
 */
import { ref, watch } from 'vue'
import { costVariance } from '@/api/cost'

const props = defineProps({ projectId: { type: String, default: '' } })
const frozen = ref(false)
const reason = ref('')

async function check() {
  frozen.value = false
  reason.value = ''
  if (!props.projectId) return
  try {
    const { data } = await costVariance(props.projectId)
    frozen.value = !!data.frozen
    reason.value = data.freezeReason
  } catch (e) {
    // 读取失败静默（徽标是增强提示，不阻断页面）
  }
}

watch(() => props.projectId, check, { immediate: true })
</script>
