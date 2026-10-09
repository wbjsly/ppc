<template>
  <el-drawer :model-value="visible" @update:model-value="v => emit('update:visible', v)"
             :title="`影响分析：${row?.itemCode || ''}`" size="480px" @open="load">
    <template v-if="data">
      <h4>替代引用（真实数据，硬阻断项）</h4>
      <el-table v-if="data.substituteSources.length" :data="data.substituteSources" size="small">
        <el-table-column prop="itemCode" label="引用方编码" width="140" />
        <el-table-column prop="itemName" label="引用方名称" />
      </el-table>
      <el-empty v-else description="无替代引用" :image-size="60" />

      <h4 style="margin-top: 18px;">下游业务引用（未关闭 PO / SO / BOM）</h4>
      <el-alert type="info" :closable="false"
        :title="data.downstreamNote"
        description="当前为占位实现：采购/销售/生产模块尚未接入，此处不展示确定性「无引用」结论；接入后将列出真实单据清单并作为停用硬阻断依据。" />
    </template>
    <el-skeleton v-else :rows="4" animated />
  </el-drawer>
</template>

<script setup>
import { ref } from 'vue'
import { getImpactApi } from '@/api/mdm/item-disable'

const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: null }
})
const emit = defineEmits(['update:visible'])

const data = ref(null)

async function load() {
  data.value = null
  if (!props.row?.id) return
  const res = await getImpactApi(props.row.id)
  data.value = res.data
}
</script>

<style scoped>
h4 { font-size: 14px; margin: 0 0 8px; }
</style>
