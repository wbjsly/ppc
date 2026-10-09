<template>
  <el-dialog :model-value="visible" @update:model-value="v => emit('update:visible', v)"
             :title="`替代维护：${row?.itemCode || ''}`" width="560px" @open="onOpen">
    <el-form label-width="130px">
      <el-form-item label="当前指向">
        <span v-if="row?.altItemCode" style="font-weight: 600;">{{ row.altItemCode }}</span>
        <span v-else style="color: #c0c4cc;">未配置</span>
      </el-form-item>
      <el-form-item label="替代物料">
        <el-select v-model="targetCode" filterable clearable placeholder="选择启用中的物料" style="width: 100%;">
          <el-option v-for="o in options" :key="o.code" :label="`[${o.code}] ${o.name}`" :value="o.code" />
        </el-select>
        <div class="form-tip">仅显示启用中物料；校验：已发布 / 非自身 / 替代链不成环（行 477 + 间接环防护）</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button v-if="row?.altItemCode" type="danger" plain :loading="saving" @click="clear">清除替代</el-button>
      <el-button type="primary" :disabled="!targetCode || targetCode === row?.altItemCode"
                 :loading="saving" @click="submit">确认设置</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { setSubstituteApi, clearSubstituteApi, getItemsOptionsApi } from '@/api/mdm/substitute'

const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: null }
})
const emit = defineEmits(['update:visible', 'saved'])

const targetCode = ref('')
const options = ref([])
const saving = ref(false)

async function onOpen() {
  targetCode.value = props.row?.altItemCode || ''
  if (!options.value.length) {
    const res = await getItemsOptionsApi()
    options.value = res.data
  }
}

async function submit() {
  saving.value = true
  try {
    await setSubstituteApi(props.row.id, targetCode.value)
    ElMessage.success('替代关系已设置')
    emit('update:visible', false)
    emit('saved')
  } catch (e) {
    // 三校验错误已由 request 拦截器单点提示
  } finally {
    saving.value = false
  }
}

async function clear() {
  try {
    await ElMessageBox.confirm(`确认清除「${props.row.itemCode}」的替代关系？`, '清除确认', { type: 'warning' })
  } catch (e) {
    return // 用户取消
  }
  saving.value = true
  try {
    await clearSubstituteApi(props.row.id)
    ElMessage.success('已清除替代')
    emit('update:visible', false)
    emit('saved')
  } catch (e) {
    // 业务阻断已单点提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-tip { font-size: 12px; color: #909399; line-height: 1.5; margin-top: 2px; }
</style>
