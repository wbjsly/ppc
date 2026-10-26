<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">考勤申诉（10.3.3）</span>
        <div>
          <el-date-picker v-model="period" type="month" value-format="YYYYMM" style="width:130px;margin-right:8px" @change="load" />
          <el-button type="primary" :disabled="!selection.length" @click="openAppeal">
            批量申诉（已选 {{ selection.length }}）
          </el-button>
          <el-button @click="openMakeup">补卡申请</el-button>
        </div>
      </div>
      <el-table :data="myExceptions" height="420" @selection-change="s => selection = s">
        <el-table-column type="selection" width="45" :selectable="r => r.status === 'IDENTIFIED'" />
        <el-table-column prop="excDate" label="日期" width="110" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ { LATE: '迟到', EARLY: '早退', MISSING: '缺卡', ABSENT: '旷工' }[row.excType] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detailMin" label="分钟" width="80" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ statusText(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="appealReason" label="申诉原因" min-width="180" />
        <el-table-column prop="evidenceRef" label="佐证" min-width="140" />
      </el-table>
    </el-card>

    <!-- 批量申诉 -->
    <el-dialog v-model="appealVisible" :title="`批量申诉（${selection.length} 条）`" width="480px">
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
                title="申诉须在异常发生后 5 个工作日内（ATTEND_APPEAL_DAYS），逾期视为默认接受" />
      <el-form label-width="80px">
        <el-form-item label="申诉原因"><el-input v-model="appealForm.reason" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="佐证引用">
          <el-input v-model="appealForm.evidenceRef" placeholder="系统内单据号（外勤单/出差单）或文字说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="appealVisible=false">取消</el-button>
        <el-button type="primary" @click="submitAppeal">提交</el-button>
      </template>
    </el-dialog>

    <!-- 补卡申请 -->
    <el-dialog v-model="makeupVisible" title="补卡申请" width="440px">
      <el-form label-width="80px">
        <el-form-item label="日期"><el-date-picker v-model="makeupForm.makeupDate" type="date" value-format="YYYY-MM-dd" style="width:100%" /></el-form-item>
        <el-form-item label="方向">
          <el-radio-group v-model="makeupForm.dir">
            <el-radio value="IN">上班</el-radio>
            <el-radio value="OUT">下班</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="原因"><el-input v-model="makeupForm.reason" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="makeupVisible=false">取消</el-button>
        <el-button type="primary" @click="submitMakeupFn">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { myExceptions as fetchMyExceptions, batchAppeal, submitMakeup } from '@/api/hr/attendance'

const period = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const myExceptions = ref([])
const selection = ref([])
const appealVisible = ref(false)
const appealForm = ref({ reason: '', evidenceRef: '' })
const makeupVisible = ref(false)
const makeupForm = ref({ makeupDate: '', dir: 'IN', reason: '' })

const statusText = s => ({ IDENTIFIED: '待处理', APPEALED: '申诉中', MGR_CONFIRMED: '主管确认', HR_FINAL: '已终审', CORRECTED: '已更正' }[s] || s)

async function load() {
  const { data } = await fetchMyExceptions({ period: period.value })
  myExceptions.value = data || []
}

function openAppeal() { appealForm.value = { reason: '', evidenceRef: '' }; appealVisible.value = true }
async function submitAppeal() {
  if (!appealForm.value.reason) return ElMessage.warning('申诉原因必填')
  try {
    const out = await batchAppeal({
      ids: selection.value.map(r => r.id),
      reason: appealForm.value.reason,
      evidenceRef: appealForm.value.evidenceRef
    })
    ElMessage.success(`申诉提交 ${out.data?.appealed} 条；逾期拦截 ${out.data?.expiredIds?.length || 0} 条`)
    appealVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
function openMakeup() { makeupForm.value = { makeupDate: '', dir: 'IN', reason: '' }; makeupVisible.value = true }
async function submitMakeupFn() {
  if (!makeupForm.value.makeupDate || !makeupForm.value.reason) return ElMessage.warning('日期与原因必填')
  try {
    await submitMakeup(makeupForm.value)
    ElMessage.success('补卡申请已提交（主管→HR 两级）')
    makeupVisible.value = false
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
