<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>我的离职申请（自助端，FR-4.7-4-11）</span>
          <el-button type="primary" size="small" @click="openApply">提交离职申请</el-button>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        title="提交后进入正式审批流（HR 办理）；法定提前通知期：正式员工 30 天 / 试用期 3 天，未满仅提示留痕不阻断；审批通过后在 HR 侧完成交接与结算" />

      <el-table :data="rows" size="small" border>
        <el-table-column prop="exitNo" label="单号" width="140" />
        <el-table-column prop="quitDate" label="拟离职日" width="110" />
        <el-table-column prop="reason" label="原因" min-width="160" />
        <el-table-column label="通知期" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.noticeOk === '0'" type="warning" size="small">未满（已留痕）</el-tag>
            <el-tag v-else type="success" size="small">已满足</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="挽留意见" min-width="140">
          <template #default="{ row }">
            <span class="muted">{{ row.retainNote || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="applyVisible" title="提交离职申请" width="460px">
      <el-form label-width="90px">
        <el-form-item label="拟离职日">
          <el-date-picker v-model="applyForm.quitDate" type="date" value-format="YYYY-MM-DD" />
          <div class="muted">正式员工请预留 30 天以上通知期</div>
        </el-form-item>
        <el-form-item label="离职原因"><el-input v-model="applyForm.reason" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" @click="doApply">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { myExitApply, myExitList } from '@/api/hr/employee-archive'

const rows = ref([])
const applyVisible = ref(false)
const applyForm = ref({})

const statusName = (s) => ({ APPLYING: '待提交审批', APPROVING: '审批中', HANDOVER: '交接办理中',
  SETTLED: '结算完成', EXITED: '已离场', REJECTED: '未通过' }[s] || s)

async function load() {
  try { rows.value = (await myExitList()).data || [] } catch (e) { /* ignore */ }
}
function openApply() {
  applyForm.value = { quitDate: new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10), reason: '' }
  applyVisible.value = true
}
async function doApply() {
  if (!applyForm.value.quitDate || !applyForm.value.reason) { ElMessage.warning('日期与原因必填'); return }
  try {
    const { data } = await myExitApply(applyForm.value)
    ElMessage.warning(data.noticeHint || '已提交')
    applyVisible.value = false
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
</style>
