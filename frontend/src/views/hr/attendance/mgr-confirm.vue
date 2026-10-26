<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">主管确认（10.3.4）</span>
        <div>
          <el-button type="success" :disabled="!approveSel.length"
                     @click="confirmBatch">批量通过（{{ approveSel.length }}）</el-button>
          <el-button type="danger" :disabled="!rejectSel.length" @click="openReject">
            批量驳回（{{ rejectSel.length }}）
          </el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="appealed" height="440" @selection-change="onSelect">
        <el-table-column type="selection" width="45" />
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="empName" label="姓名" width="100" />
        <el-table-column prop="excDate" label="日期" width="110" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ { LATE: '迟到', EARLY: '早退', MISSING: '缺卡', ABSENT: '旷工' }[row.excType] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="appealReason" label="申诉原因" min-width="180" />
        <el-table-column prop="evidenceRef" label="佐证" min-width="140" />
      </el-table>
    </el-card>

    <!-- 驳回意见 -->
    <el-dialog v-model="rejectVisible" :title="`驳回 ${rejectSel.length} 条`" width="440px">
      <el-input v-model="rejectOpinion" type="textarea" :rows="3" placeholder="逐条驳回意见（佐证无效退回后员工可重新申诉）" />
      <template #footer>
        <el-button @click="rejectVisible=false">取消</el-button>
        <el-button type="danger" @click="submitReject">驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { exceptionList, mgrConfirm, pendingConfirmations } from '@/api/hr/attendance'

const appealed = ref([])
const selection = ref([])
const rejectVisible = ref(false)
const rejectOpinion = ref('')

const approveSel = ref([])
const rejectSel = ref([])

function onSelect(s) {
  selection.value = s
  approveSel.value = s
  rejectSel.value = s
}

async function load() {
  const [e, p] = await Promise.all([exceptionList({ status: 'APPEALED' }), pendingConfirmations()])
  appealed.value = e.data || []
  const deptWarn = (p.data || []).map(d => `${d.deptName}(${d.count})`).join('、')
  if (deptWarn) ElMessage.info(`催办清单：${deptWarn}（截止日前 3 工作日）`)
}

async function confirmBatch() {
  await mgrConfirm({ approveIds: selection.value.map(r => r.id), opinion: '确认通过' })
  ElMessage.success('批量确认完成'); load()
}
function openReject() { rejectOpinion.value = ''; rejectVisible.value = true }
async function submitReject() {
  await mgrConfirm({
    rejectIds: selection.value.map(r => ({ id: r.id, opinion: rejectOpinion.value })),
    opinion: rejectOpinion.value
  })
  ElMessage.success('驳回完成（回退待处理，员工可重新申诉）'); rejectVisible.value = false; load()
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
