<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="沙箱联调（15.6）：沙箱配置、联调测试、生产放行"
      description="复用 interface-onboarding。沙箱通过率须达 100% 方可申请生产放行（BR-5.5-02）；放行双人会签（C-0-03）。" />

    <!-- 15.6.3 生产放行 -->
    <div class="tab-bar">
      <el-button type="primary" size="small" @click="openRelease">申请生产放行</el-button>
    </div>
    <el-table :data="releases" border size="small">
      <el-table-column prop="releaseNo" label="放行单号" width="150" />
      <el-table-column prop="contractNo" label="契约" width="150" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="relTag(row.status)">{{ relText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="observeUntil" label="观察期至" width="160">
        <template #default="{ row }">{{ row.observeUntil || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PENDING'" size="small" type="success"
                     @click="reviewRelease(row, 'APPROVED')">会签通过</el-button>
          <el-button v-if="row.status === 'PENDING'" size="small" type="danger"
                     @click="reviewRelease(row, 'REJECTED')">驳回</el-button>
          <el-button v-if="row.status === 'APPROVED' || row.status === 'OBSERVING'" size="small"
                     @click="observeComplete(row)">观察完成</el-button>
          <el-button v-if="row.status === 'OBSERVABLE' || row.status === 'OBSERVING'" size="small"
                     type="warning" @click="daily(row)">观察日报</el-button>
          <el-button v-if="row.status === 'OBSERVED'" size="small" type="primary"
                     @click="archive(row)">归档放行</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-alert v-if="!releases.length" type="info" :closable="false" style="margin-top:10px"
              title="无放行单（通过率 100% 后可申请；流程：申请 → 双人会签 → 观察期 → 归档，BR-5.5-02 / C-0-03）" />
    <el-alert v-if="dailyText" type="info" :closable="false" style="margin-top:10px"
              :title="`观察日报：${dailyText}`" />

    <!-- 申请放行抽屉 -->
    <el-drawer title="申请生产放行" size="440px" v-model="relDrawer">
      <el-form label-width="90px" size="small">
        <el-form-item label="契约" required>
          <el-select v-model="relForm.contractId" filterable style="width:100%" placeholder="选择契约（须通过率 100%）">
            <el-option v-for="c in contracts" :key="c.id" :label="`${c.contractNo} ${c.name}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="观察期(天)"><el-input v-model="relForm.observeDays" placeholder="7" /></el-form-item>
        <el-form-item label="说明"><el-input type="textarea" :rows="3" v-model="relForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="relDrawer=false">取消</el-button>
        <el-button type="primary" @click="requestRelease">提交申请</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 15.6.3 生产放行（拆分自原沙箱联调操作台）：放行申请 / 会签 / 观察 / 归档。
 * 共享契约列表见 useSandbox.js；放行单状态为本页独有，留页内。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { requestReleaseApi, getReleasesApi, reviewReleaseApi,
  observeCompleteApi, archiveReleaseApi, getObservationDailyApi } from '@/api/proc/interface-integration'
import { useSandbox } from './useSandbox'

const { contracts, loadContracts } = useSandbox()

const releases = ref([])
const dailyText = ref('')
const relDrawer = ref(false)
const relForm = ref({ contractId: '', observeDays: '7', remark: '' })

function relTag(s) {
  return { PENDING: 'warning', APPROVED: 'primary', OBSERVING: '', OBSERVED: 'success', ARCHIVED: 'success', REJECTED: 'danger' }[s] || 'info'
}
function relText(s) {
  return { PENDING: '待会签', APPROVED: '已会签', OBSERVING: '观察中', OBSERVED: '观察完成', ARCHIVED: '已放行', REJECTED: '已驳回' }[s] || s
}

async function load() {
  await loadContracts()
  try { const r = await getReleasesApi(); releases.value = r.data?.data || [] } catch { releases.value = [] }
}
onMounted(load)

function openRelease() {
  relForm.value = { contractId: '', observeDays: '7', remark: '' }
  relDrawer.value = true
}
async function requestRelease() {
  if (!relForm.value.contractId) { ElMessage.warning('选择契约'); return }
  try {
    await requestReleaseApi(relForm.value)
    ElMessage.success('放行申请已提交（双人会签）')
    relDrawer.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '申请被阻断（须沙箱通过率 100%，BR-5.5-02）')
  }
}
async function reviewRelease(row, opinion) {
  await reviewReleaseApi(row.id, opinion)
  ElMessage.success(opinion === 'APPROVED' ? '会签通过，进入观察期' : '已驳回')
  load()
}
async function observeComplete(row) {
  await observeCompleteApi(row.id)
  ElMessage.success('观察期完成')
  load()
}
async function daily(row) {
  try {
    const r = await getObservationDailyApi(row.id)
    const d = r.data?.data
    dailyText.value = typeof d === 'string' ? d : JSON.stringify(d)
  } catch { dailyText.value = '暂无观察日报' }
}
async function archive(row) {
  await ElMessageBox.confirm('归档放行单？归档即正式生产放行', '提示', { type: 'warning' })
  await archiveReleaseApi(row.id)
  ElMessage.success('已归档放行')
  load()
}
</script>

<style scoped>
.intf-page { padding: 12px; }
.tab-bar { margin-bottom: 10px; display: flex; justify-content: flex-end; }
</style>
