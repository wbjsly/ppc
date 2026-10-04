<template>
  <el-dialog :model-value="visible" @update:model-value="v => emit('update:visible', v)"
             :title="`${meta.title}：${row?.itemCode || ''}`" width="560px" @open="onOpen">
    <el-alert v-if="kind === 'archive'" type="warning" :closable="false" style="margin-bottom: 10px;"
      title="归档为终态：归档后不可被新业务引用，也不可直接启用。" />
    <el-form label-width="90px">
      <el-form-item :label="meta.label" required>
        <el-input v-model="reason" type="textarea" :rows="3"
          :placeholder="meta.placeholder" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="影响分析">
        <div v-if="impact" class="impact-brief">
          <div>替代引用：{{ impact.substituteTotal ? `${impact.substituteTotal} 个物料指向本物料（硬阻断项）` : '无' }}</div>
          <div>下游引用（PO/SO/BOM）：{{ impact.downstreamNote }}</div>
        </div>
        <span v-else style="color: #909399; font-size: 12px;">加载中…</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :disabled="!reason.trim()" :loading="saving" @click="submit">
        确认{{ meta.title }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { disableApi, enableApi, archiveApi, getImpactApi } from '@/api/mdm/item-disable'

const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: null },
  /** disable | enable | archive */
  kind: { type: String, default: 'disable' }
})
const emit = defineEmits(['update:visible', 'done'])

const META = {
  disable: { title: '停用', label: '停用原因', placeholder: '如：产品升级换代，停用旧料' },
  enable: { title: '启用', label: '启用原因', placeholder: '如：误停用回退，已确认可恢复引用' },
  archive: { title: '归档', label: '归档原因', placeholder: '如：长期无业务引用，归档释放主库' }
}
const meta = computed(() => META[props.kind])

const reason = ref('')
const impact = ref(null)
const saving = ref(false)

async function onOpen() {
  reason.value = ''
  impact.value = null
  if (!props.row?.id) return
  const res = await getImpactApi(props.row.id)
  impact.value = res.data
}

async function submit() {
  saving.value = true
  try {
    const r = reason.value.trim()
    if (props.kind === 'disable') await disableApi(props.row.id, r)
    else if (props.kind === 'enable') await enableApi(props.row.id, r)
    else await archiveApi(props.row.id, r)
    ElMessage.success(`${meta.value.title}成功`)
    emit('update:visible', false)
    emit('done')
  } catch (e) {
    // 业务阻断（409/422）已由 request 拦截器单点提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.impact-brief { font-size: 12px; color: #606266; line-height: 1.7; }
</style>
