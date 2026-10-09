<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="周期盘点（4.11.1）：手工选范围生成任务，生成即锁仓位"
      description="按仓库+仓位/物料筛选生成 CYCLE 任务，账面在锁生效时定格；差异 ≤0.5% 自动调整，超容差走 4.11.3 审批，>10% 阻断待复盘。" />

    <el-card shadow="never" style="margin-bottom: 12px;">
      <template #header>生成周期盘点任务</template>
      <el-form inline>
        <el-form-item label="仓库" required>
          <el-input v-model="form.warehouseCode" placeholder="如 WH-MAIN" style="width: 140px" />
        </el-form-item>
        <el-form-item label="物料（可选）">
          <el-input v-model="form.itemCode" placeholder="物料编码" style="width: 150px" />
        </el-form-item>
        <el-form-item label="仓位（可选，逗号分隔）">
          <el-input v-model="form.bins" placeholder="BIN-A01,BIN-A02" style="width: 220px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="盘点原因/范围说明" style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="creating" @click="doCreate">生成任务</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <CountTaskPanel ref="panel" task-type="CYCLE" />
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { createCycleTaskApi } from '@/api/inv/count'
import CountTaskPanel from '@/views/inv/count-shared/CountTaskPanel.vue'

// 4.11.1 周期盘点：手工选范围生成 CYCLE 任务
const creating = ref(false)
const panel = ref(null)
const form = reactive({ warehouseCode: 'WH-MAIN', itemCode: '', bins: '', remark: '' })

async function doCreate() {
  if (!form.warehouseCode.trim()) {
    ElMessage.warning('盘点仓库必填')
    return
  }
  creating.value = true
  try {
    const res = await createCycleTaskApi({
      warehouseCode: form.warehouseCode.trim(),
      itemCode: form.itemCode.trim() || undefined,
      binCodes: form.bins.split(',').map(s => s.trim()).filter(Boolean),
      remark: form.remark || undefined
    })
    ElMessage.success(`任务 ${res.data?.taskNo} 已生成（${res.data?.totalLines} 行），仓位已锁定`)
    panel.value?.reload(1)
  } catch (e) {
    console.warn('[count-cycle] create failed', e)
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.page { padding: 4px; }
</style>
