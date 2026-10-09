<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="全面盘点（4.11.2）：一键对选仓全量生成任务"
      description="生成 FULL 任务覆盖该仓全部有货维度（仓位×物料×批次），生成即锁仓位；'' 未分配位不入盘点范围。" />

    <el-card shadow="never" style="margin-bottom: 12px;">
      <template #header>一键全面盘点</template>
      <el-form inline>
        <el-form-item label="仓库" required>
          <el-input v-model="warehouseCode" placeholder="如 WH-MAIN" style="width: 140px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="remark" placeholder="盘点原因/范围说明" style="width: 240px" />
        </el-form-item>
        <el-form-item>
          <el-button type="warning" :loading="creating" @click="doCreate">一键生成全盘任务</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <CountTaskPanel ref="panel" task-type="FULL" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createFullTaskApi } from '@/api/inv/count'
import CountTaskPanel from '@/views/inv/count-shared/CountTaskPanel.vue'

// 4.11.2 全面盘点：一键全仓生成 FULL 任务
const creating = ref(false)
const panel = ref(null)
const warehouseCode = ref('WH-MAIN')
const remark = ref('')

async function doCreate() {
  if (!warehouseCode.value.trim()) {
    ElMessage.warning('盘点仓库必填')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将对仓库 ${warehouseCode.value.trim()} 生成全面盘点任务并锁定全部有货仓位，确认？`,
      '一键全面盘点', { type: 'warning' })
  } catch (e) {
    return
  }
  creating.value = true
  try {
    const res = await createFullTaskApi({
      warehouseCode: warehouseCode.value.trim(),
      remark: remark.value || undefined
    })
    ElMessage.success(`全盘任务 ${res.data?.taskNo} 已生成（${res.data?.totalLines} 行），仓位已锁定`)
    panel.value?.reload(1)
  } catch (e) {
    console.warn('[count-full] create failed', e)
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.page { padding: 4px; }
</style>
