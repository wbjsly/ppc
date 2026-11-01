<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="沙箱联调（15.6）：沙箱配置、联调测试、生产放行"
      description="复用 interface-onboarding。沙箱通过率须达 100% 方可申请生产放行（BR-5.5-02）；放行双人会签（C-0-03）。" />

    <el-tabs v-model="tab">
      <!-- 15.6.1 沙箱配置 -->
      <el-tab-pane label="沙箱配置" name="config">
        <div class="tab-bar">
          <el-button type="primary" size="small" @click="caseDrawer=true">新增测试用例</el-button>
        </div>
        <el-table :data="cases" border size="small">
          <el-table-column prop="caseNo" label="用例号" width="150" />
          <el-table-column prop="contractNo" label="契约" width="150" />
          <el-table-column prop="caseName" label="用例名" min-width="160" />
          <el-table-column prop="expectResult" label="期望结果" width="110" />
          <el-table-column prop="actualResult" label="实际结果" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.actualResult" size="small"
                      :type="row.actualResult === row.expectResult ? 'success' : 'danger'">
                {{ row.actualResult }}
              </el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="executedAt" label="执行时间" width="160">
            <template #default="{ row }">{{ row.executedAt || '未执行' }}</template>
          </el-table-column>
        </el-table>
        <el-alert v-if="!cases.length" type="info" :closable="false" style="margin-top:10px"
                  title="暂无测试用例（沙箱环境按契约配置请求样例与期望结果）" />
      </el-tab-pane>

      <!-- 15.6.2 联调测试 -->
      <el-tab-pane label="联调测试" name="test">
        <el-table :data="contracts" border size="small">
          <el-table-column prop="contractNo" label="契约号" width="150" />
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column prop="version" label="版本" width="90" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button size="small" @click="passRate(row)">联调通过率</el-button>
              <el-button size="small" type="primary" @click="simulate(row)">跑一次模拟</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert v-if="rate != null" :closable="false" style="margin-top:12px"
          :type="rate >= 100 ? 'success' : 'warning'"
          :title="`沙箱联调通过率：${rate}%（${rate >= 100 ? '可申请生产放行' : '未达 100%，不可放行'}）`" />
      </el-tab-pane>

      <!-- 15.6.3 生产放行 -->
      <el-tab-pane label="生产放行" name="release">
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
      </el-tab-pane>
    </el-tabs>

    <!-- 新增用例抽屉 -->
    <el-drawer title="新增沙箱测试用例" size="440px" v-model="caseDrawer">
      <el-form label-width="90px" size="small">
        <el-form-item label="契约" required>
          <el-select v-model="cf.contractId" filterable style="width:100%" placeholder="选择契约">
            <el-option v-for="c in contracts" :key="c.id" :label="`${c.contractNo} ${c.name}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="用例名" required><el-input v-model="cf.caseName" placeholder="如：正常下单" /></el-form-item>
        <el-form-item label="期望结果" required>
          <el-select v-model="cf.expectResult" style="width:100%">
            <el-option label="PASS" value="PASS" />
            <el-option label="FAIL（负向用例）" value="FAIL" />
          </el-select>
        </el-form-item>
        <el-form-item label="请求样例"><el-input type="textarea" :rows="4" v-model="cf.payloadJson" placeholder='{"orderNo":"..."}' /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseDrawer=false">取消</el-button>
        <el-button type="primary" @click="addCase">保存</el-button>
      </template>
    </el-drawer>

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
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getContractsApi, getPassRateApi, addSandboxCaseApi, getSandboxCasesApi,
  runSimulationApi, requestReleaseApi, getReleasesApi, reviewReleaseApi,
  observeCompleteApi, archiveReleaseApi, getObservationDailyApi } from '@/api/proc/interface-integration'

const route = useRoute()
const tab = ref(route.meta.tab || 'config')

const contracts = ref([])
const cases = ref([])
const releases = ref([])
const rate = ref(null)
const dailyText = ref('')
const caseDrawer = ref(false)
const relDrawer = ref(false)
const cf = ref({ contractId: '', caseName: '', expectResult: 'PASS', payloadJson: '' })
const relForm = ref({ contractId: '', observeDays: '7', remark: '' })

function stTag(s) {
  return { PUBLISHED: 'success', DRAFT: 'info', REVIEWING: 'warning' }[s] || 'info'
}
function relTag(s) {
  return { PENDING: 'warning', APPROVED: 'primary', OBSERVING: '', OBSERVED: 'success', ARCHIVED: 'success', REJECTED: 'danger' }[s] || 'info'
}
function relText(s) {
  return { PENDING: '待会签', APPROVED: '已会签', OBSERVING: '观察中', OBSERVED: '观察完成', ARCHIVED: '已放行', REJECTED: '已驳回' }[s] || s
}

async function load() {
  try { const r = await getContractsApi(); contracts.value = r.data?.data || [] } catch { contracts.value = [] }
  try { const r = await getSandboxCasesApi(); cases.value = r.data?.data || [] } catch { cases.value = [] }
  try { const r = await getReleasesApi(); releases.value = r.data?.data || [] } catch { releases.value = [] }
}
onMounted(load)

async function passRate(row) {
  const r = await getPassRateApi(row.id)
  rate.value = r.data?.data?.passRate ?? null
  if (rate.value == null) ElMessage.info('暂无联调数据')
}

async function simulate(row) {
  try {
    await runSimulationApi({ contractId: row.id })
    ElMessage.success('模拟已执行')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '模拟失败')
  }
}

async function addCase() {
  if (!cf.value.contractId || !cf.value.caseName) { ElMessage.warning('契约与用例名必填'); return }
  await addSandboxCaseApi(cf.value)
  ElMessage.success('用例已保存')
  caseDrawer.value = false
  load()
}

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
