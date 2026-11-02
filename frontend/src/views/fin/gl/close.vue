<template>
  <div class="page gl-page">
    <!-- ================= 8.1.5 期末结账 ================= -->
    <el-form inline>
      <el-button type="primary" @click="loadPeriods">刷新</el-button>
      <el-button v-if="curPeriod" type="danger" @click="doChecklist">执行结账检查</el-button>
    </el-form>
    <el-alert v-if="curPeriod" :title="`当前期间 ${curPeriod.period}（${curPeriod.status === 'CLOSED' ? '已关闭' : '开放中'}）`"
              :type="curPeriod.status === 'CLOSED' ? 'info' : 'success'" :closable="false"
              style="margin-bottom:12px" />
    <el-table :data="periodRows" border size="small" style="margin-bottom:16px">
      <el-table-column prop="period" label="期间" width="110" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'CLOSED' ? 'info' : 'success'" size="small">
            {{ row.status === 'CLOSED' ? '已结账' : '开放' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="closeBy" label="结账人" width="110" />
      <el-table-column prop="closeAt" label="结账时间" width="170" />
    </el-table>
    <template v-if="checkResult">
      <h4 style="margin:8px 0">结账检查清单</h4>
      <el-table :data="checkResult.items" border size="small">
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? '通过' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="检查项" width="280" />
        <el-table-column prop="detail" label="明细" min-width="260" show-overflow-tooltip />
      </el-table>
      <el-button type="danger" style="margin-top:12px" :disabled="!checkResult.allOk"
                 @click="doClose">执行结账（CLOSE）</el-button>
      <span v-if="!checkResult.allOk" style="margin-left:10px;color:#f56c6c">检查项未全绿，不可结账</span>
    </template>
  </div>
</template>

<script setup>
/**
 * 8.1.5 期末结账（拆分自原总账多 tab 页，design D1）。
 * 本页独有逻辑，无跨页共享状态（D2：无共享未抽）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { periodCurrent, periodList, periodChecklist, periodClose } from '@/api/fin/gl'

const curPeriod = ref(null)
const periodRows = ref([])
const checkResult = ref(null)
async function loadPeriods() {
  const c = await periodCurrent()
  curPeriod.value = c.data
  const { data } = await periodList()
  periodRows.value = data || []
}
async function doChecklist() {
  const { data } = await periodChecklist(curPeriod.value.period)
  checkResult.value = data
}
async function doClose() {
  await ElMessageBox.confirm(`确认结账 ${curPeriod.value.period}？结账后该期间禁止新过账。`, '期末结账', { type: 'warning' })
  const { data } = await periodClose(curPeriod.value.period)
  ElMessage.success(`期间 ${data.period} 已结账`)
  checkResult.value = null
  loadPeriods()
}

onMounted(() => {
  loadPeriods()
})
</script>
