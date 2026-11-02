<template>
  <div class="page">
    <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
              title="开启后（结账保障期 1~5 日语义）非紧急变更提交将被阻断（BR-5.8-14）" />
    <el-switch v-model="guard" active-text="保障开启" inactive-text="保障关闭" @change="toggleGuard" />
  </div>
</template>

<script setup>
/**
 * 18.4.3 结账保障（拆分自原容量运维操作台）：保障开关。
 * 单页独有逻辑，未抽 composable。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { guardStatusApi, setGuardApi } from '@/api/ops/ops-sla'

const guard = ref(false)

onMounted(async () => {
  const r = await guardStatusApi()
  guard.value = r.data.enabled
})

async function toggleGuard(v) {
  await setGuardApi(v)
  ElMessage.success(v ? '结账保障已开启' : '结账保障已关闭')
}
</script>
