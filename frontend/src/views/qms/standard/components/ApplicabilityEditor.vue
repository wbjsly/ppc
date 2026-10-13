<template>
  <div class="appl-editor">
    <div class="ae-toolbar">
      <span class="ae-title">适用范围（五维度：物料/品类/工序/供方/客户；优先级数值越小越具体）</span>
      <div>
        <el-button v-if="editable" size="small" @click="rows.push({ priority: 50 })">+ 范围</el-button>
        <el-button v-if="editable" size="small" type="primary" :loading="saving" @click="save">保存范围</el-button>
      </div>
    </div>
    <el-table :data="rows" size="small" border>
      <el-table-column label="物料编码" width="140">
        <template #default="{ row }">
          <el-input v-model="row.materialCode" size="small" :disabled="!editable" placeholder="ITEM-xxx" />
        </template>
      </el-table-column>
      <el-table-column label="品类编码" width="120">
        <template #default="{ row }">
          <el-input v-model="row.categoryCode" size="small" :disabled="!editable" placeholder="CAT-xxx" />
        </template>
      </el-table-column>
      <el-table-column label="工序" width="110">
        <template #default="{ row }">
          <el-input v-model="row.processId" size="small" :disabled="!editable" placeholder="OP-xxx" />
        </template>
      </el-table-column>
      <el-table-column label="供应商" width="120">
        <template #default="{ row }">
          <el-input v-model="row.supplierId" size="small" :disabled="!editable" placeholder="SUP-xxx" />
        </template>
      </el-table-column>
      <el-table-column label="客户" width="120">
        <template #default="{ row }">
          <el-input v-model="row.customerId" size="small" :disabled="!editable" placeholder="CUST-xxx" />
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="90">
        <template #default="{ row }">
          <el-input v-model="row.priority" size="small" :disabled="!editable" />
        </template>
      </el-table-column>
      <el-table-column v-if="editable" label="" width="55">
        <template #default="{ $index }">
          <el-button link type="danger" @click="rows.splice($index, 1)">删</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="ae-hint">每行至少一个非空维度；发布时系统校验适用范围与生效区间冲突（C-4.12-18）。</div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getStandardDetailApi, updateVersionApi, createVersionApi } from '@/api/qms/standard'

const props = defineProps({
  standardId: { type: String, required: true },
  versionId: { type: String, default: null },
  editable: { type: Boolean, default: true },
  versionPayload: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['saved'])

const rows = ref([])
const saving = ref(false)

async function load() {
  if (!props.versionId) { rows.value = [{ priority: 50 }]; return }
  try {
    const res = await getStandardDetailApi(props.standardId)
    const appl = (res.data?.applicabilities || []).filter(a => a.versionId === props.versionId)
    rows.value = appl.length ? appl.map(a => ({ ...a })) : [{ priority: 50 }]
  } catch (e) { /* 拦截器提示 */ }
}

async function save() {
  const filled = rows.value.filter(r =>
    [r.materialCode, r.categoryCode, r.processId, r.supplierId, r.customerId].some(v => v && String(v).trim()))
  if (!filled.length) { ElMessage.warning('至少配置一行有效维度'); return }
  saving.value = true
  try {
    const payload = { ...props.versionPayload, applicabilities: filled }
    if (props.versionId) await updateVersionApi(props.versionId, payload)
    else await createVersionApi(props.standardId, payload)
    ElMessage.success('适用范围已保存')
    emit('saved')
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

watch(() => props.versionId, load, { immediate: true })

defineExpose({ reload: load })
</script>

<style scoped>
.ae-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.ae-title { font-weight: 600; font-size: 14px; }
.ae-hint { margin-top: 6px; font-size: 12px; color: #909399; }
</style>
