<template>
  <div class="page">
    <el-form inline>
      <el-form-item label="日期">
        <el-date-picker v-model="form.dutyDate" type="date" value-format="YYYY-MM-DD" style="width: 160px" />
      </el-form-item>
      <el-form-item label="层级">
        <el-select v-model="form.level" style="width: 100px">
          <el-option label="L1" value="L1" /><el-option label="L2" value="L2" /><el-option label="L3" value="L3" />
        </el-select>
      </el-form-item>
      <el-form-item label="值班人">
        <el-input v-model="form.userName" style="width: 140px" />
      </el-form-item>
      <el-button type="primary" @click="saveOncall">保存排班</el-button>
    </el-form>
    <el-table :data="oncall" border size="small">
      <el-table-column prop="dutyDate" label="日期" width="120" />
      <el-table-column prop="onLevel" label="层级" width="80" />
      <el-table-column prop="userName" label="值班人" />
      <el-table-column prop="backupUser" label="备份" />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.3.1 值班看板（拆分自原升级处置操作台）：值班排班与近 7 日台账。
 * 单页独有逻辑，未抽 composable。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { oncallApi, saveOncallApi } from '@/api/ops/ops-sla'

const oncall = ref([])
const form = ref({ dutyDate: '', level: 'L1', userName: '' })

onMounted(loadOncall)

async function loadOncall() {
  const r = await oncallApi(7)
  oncall.value = r.data || []
}

async function saveOncall() {
  if (!form.value.dutyDate || !form.value.userName) {
    ElMessage.warning('日期与值班人必填')
    return
  }
  await saveOncallApi(form.value.dutyDate, form.value.level, form.value.userName)
  ElMessage.success('排班已保存')
  loadOncall()
}
</script>
