<template>
  <div class="char-editor">
    <div class="ce-toolbar">
      <span class="ce-title">检验特性（CTQ 至少一条；计量型须上下限）</span>
      <div>
        <el-button v-if="editable" size="small" @click="rows.push(blank())">+ 特性</el-button>
        <el-button v-if="editable" size="small" type="primary" :loading="saving" @click="save">保存特性</el-button>
      </div>
    </div>
    <el-table :data="rows" size="small" border v-loading="loading">
      <el-table-column label="序号" width="55" align="center">
        <template #default="{ $index }">{{ $index + 1 }}</template>
      </el-table-column>
      <el-table-column label="特性名" min-width="140">
        <template #default="{ row }">
          <el-input v-model="row.characteristicName" size="small" :disabled="!editable" />
        </template>
      </el-table-column>
      <el-table-column label="CTQ" width="65" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.ctqFlag" active-value="1" inactive-value="0" :disabled="!editable" />
        </template>
      </el-table-column>
      <el-table-column label="安全/法规" width="85" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.regulatoryFlag" active-value="1" inactive-value="0" :disabled="!editable" />
        </template>
      </el-table-column>
      <el-table-column label="规格类型" width="115">
        <template #default="{ row }">
          <el-select v-model="row.specType" size="small" :disabled="!editable">
            <el-option label="计量型" value="NUMERIC" />
            <el-option label="计数型" value="COUNT" />
            <el-option label="外观" value="LOOK" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="下限" width="95">
        <template #default="{ row }">
          <el-input v-model="row.lowerLimit" size="small" :disabled="!editable || row.specType !== 'NUMERIC'" />
        </template>
      </el-table-column>
      <el-table-column label="上限" width="95">
        <template #default="{ row }">
          <el-input v-model="row.upperLimit" size="small" :disabled="!editable || row.specType !== 'NUMERIC'" />
        </template>
      </el-table-column>
      <el-table-column label="目标值" width="95">
        <template #default="{ row }">
          <el-input v-model="row.targetValue" size="small" :disabled="!editable" />
        </template>
      </el-table-column>
      <el-table-column label="单位" width="75">
        <template #default="{ row }">
          <el-input v-model="row.unit" size="small" :disabled="!editable" />
        </template>
      </el-table-column>
      <el-table-column label="方法" min-width="110">
        <template #default="{ row }">
          <el-input v-model="row.methodName" size="small" :disabled="!editable" />
        </template>
      </el-table-column>
      <el-table-column v-if="editable" label="" width="55">
        <template #default="{ $index }">
          <el-button link type="danger" @click="rows.splice($index, 1)">删</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!rows.length && !loading" class="ce-empty">暂无特性，点击「+ 特性」添加</div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getCharacteristicsApi, updateVersionApi, createVersionApi } from '@/api/qms/standard'

const props = defineProps({
  standardId: { type: String, required: true },
  versionId: { type: String, default: null },
  editable: { type: Boolean, default: true },
  /** 新建草稿时携带的版本基础字段（生效日期/变更原因等），保证 create 场景可提交 */
  versionPayload: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['saved'])

const rows = ref([])
const loading = ref(false)
const saving = ref(false)

function blank() {
  return {
    characteristicName: '', ctqFlag: '1', regulatoryFlag: '0', specType: 'NUMERIC',
    lowerLimit: '', upperLimit: '', targetValue: '', unit: '', methodName: ''
  }
}

async function load() {
  if (!props.versionId) { rows.value = [blank()]; return }
  loading.value = true
  try {
    const res = await getCharacteristicsApi(props.versionId)
    rows.value = (res.data || []).length ? res.data.map(c => ({ ...c })) : [blank()]
  } catch (e) { /* 拦截器提示 */ } finally { loading.value = false }
}

async function save() {
  // 前置校验：至少一条 CTQ；计量型上下限必填（与后端 C-4.12-18 一致）
  if (!rows.value.some(r => r.ctqFlag === '1')) {
    ElMessage.warning('CTQ 特性至少一条'); return
  }
  for (const r of rows.value) {
    if (!r.characteristicName) { ElMessage.warning('特性名称必填'); return }
    if (r.specType === 'NUMERIC' && (r.lowerLimit === '' || r.lowerLimit == null || r.upperLimit === '' || r.upperLimit == null)) {
      ElMessage.warning(`计量型特性「${r.characteristicName}」须填上下限`); return
    }
  }
  saving.value = true
  try {
    const payload = { ...props.versionPayload, characteristics: rows.value }
    if (props.versionId) await updateVersionApi(props.versionId, payload)
    else await createVersionApi(props.standardId, payload)
    ElMessage.success('特性已保存')
    emit('saved')
  } catch (e) { /* 拦截器提示（422 由拦截器展示） */ } finally { saving.value = false }
}

watch(() => props.versionId, load, { immediate: true })

defineExpose({ reload: load })
</script>

<style scoped>
.ce-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.ce-title { font-weight: 600; font-size: 14px; }
.ce-empty { text-align: center; color: #909399; padding: 20px 0; font-size: 13px; }
</style>
