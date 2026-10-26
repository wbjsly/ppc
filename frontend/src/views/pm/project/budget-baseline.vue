<template>
  <div class="pm-container">
    <!-- 项目选择与状态条 -->
    <el-card style="margin-bottom: 12px;">
      <div class="pick-row">
        <span class="label">当前项目：</span>
        <el-select v-model="projectId" filterable placeholder="选择项目" style="width: 320px;" @change="onProjectChange">
          <el-option v-for="p in projects" :key="p.id"
            :label="`${p.projectNo || '（草稿）'} ${p.name}`" :value="p.id" />
        </el-select>
        <el-tag v-if="project.status" :type="statusType(project.status)" size="small">
          {{ statusName(project.status) }}
        </el-tag>
        <el-tag v-if="project.baselineLocked === '1'" type="danger" size="small">基线已锁定</el-tag>
        <ProjectFreezeBadge :project-id="projectId" />
        <span v-if="project.rejectOpinion" class="reject">驳回意见：{{ project.rejectOpinion }}</span>
        <div class="right-actions">
          <el-button v-if="projectId && project.baselineLocked !== '1'" type="primary"
            :loading="submitting" @click="submitBaseline">提交基线审批</el-button>
          <el-button v-if="project.baselineLocked === '1'" type="warning" plain
            @click="openChange">预算变更申请</el-button>
        </div>
      </div>
    </el-card>

    <template v-if="projectId">
      <!-- ==================== 预算行 ==================== -->
      <el-card>
        <template #header>
          <div class="card-header">
            <span>任务级预算（人工/间接自动计算；分摊率 {{ project.fenplanRate }}）</span>
            <el-button v-if="!readonly" type="primary" size="small" :loading="saving" @click="saveLines">保存预算</el-button>
          </div>
        </template>
        <el-table :data="lines" v-loading="loading" stripe show-summary :summary-method="summaryMethod">
          <el-table-column prop="wbsCode" label="WBS 编码" width="110" />
          <el-table-column prop="wbsName" label="任务" min-width="160" show-overflow-tooltip />
          <el-table-column label="工时" width="80" align="right">
            <template #default="{ row }">{{ row.estHours ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="职级费率" width="100" align="right">
            <template #default="{ row }">{{ rateOf(row) }}</template>
          </el-table-column>
          <el-table-column label="人工（自动）" width="110" align="right">
            <template #default="{ row }">{{ fmt(row.labor) }}</template>
          </el-table-column>
          <el-table-column label="材料" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.material" :min="0" :precision="2" :disabled="readonly"
                size="small" controls-position="right" style="width: 110px;" @change="recalcRow(row)" />
            </template>
          </el-table-column>
          <el-table-column label="外协" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.subcontract" :min="0" :precision="2" :disabled="readonly"
                size="small" controls-position="right" style="width: 110px;" @change="recalcRow(row)" />
            </template>
          </el-table-column>
          <el-table-column label="差旅" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.travel" :min="0" :precision="2" :disabled="readonly"
                size="small" controls-position="right" style="width: 110px;" @change="recalcRow(row)" />
            </template>
          </el-table-column>
          <el-table-column label="其他" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.other" :min="0" :precision="2" :disabled="readonly"
                size="small" controls-position="right" style="width: 110px;" @change="recalcRow(row)" />
            </template>
          </el-table-column>
          <el-table-column label="间接（自动）" width="110" align="right">
            <template #default="{ row }">{{ fmt(row.indirect) }}</template>
          </el-table-column>
          <el-table-column label="小计" width="110" align="right">
            <template #default="{ row }">{{ fmt(lineTotal(row)) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!lines.length" description="暂无预算行——先到 11.3.2 任务分解（任务级自动生成预算行）" />
      </el-card>

      <!-- ==================== 预算变更单 ==================== -->
      <el-card style="margin-top: 12px;">
        <template #header>
          <div class="card-header">
            <span>预算变更申请（BR-4.8-12：基线锁定后调整须 PMO→财务双审批）</span>
          </div>
        </template>
        <el-table :data="changes" stripe>
          <el-table-column prop="changeNo" label="变更单号" width="150" />
          <el-table-column prop="reason" label="变更理由" min-width="180" show-overflow-tooltip />
          <el-table-column label="变更前" width="110" align="right">
            <template #default="{ row }">{{ fmt(row.beforeAmount) }}</template>
          </el-table-column>
          <el-table-column label="变更后" width="110" align="right">
            <template #default="{ row }">{{ fmt(row.afterAmount) }}</template>
          </el-table-column>
          <el-table-column label="差额" width="110" align="right">
            <template #default="{ row }">
              <span :style="{ color: row.diffAmount < 0 ? '#67c23a' : '#f56c6c' }">{{ fmt(row.diffAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'APPROVED' ? 'success' : (row.status === 'REJECTED' ? 'danger' : 'warning')" size="small">
                {{ { PENDING: '审批中', APPROVED: '已通过', REJECTED: '已驳回' }[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createBy" label="申请人" width="100" />
        </el-table>
        <el-empty v-if="!changes.length" description="暂无变更单" :image-size="70" />
      </el-card>
    </template>
    <el-empty v-else description="请先选择项目" />

    <!-- ==================== 变更申请弹窗 ==================== -->
    <el-dialog v-model="changeVisible" title="预算变更申请" width="680px">
      <el-form label-width="90px">
        <el-form-item label="变更理由" required>
          <el-input v-model="changeForm.reason" type="textarea" :rows="2" maxlength="1000"
            placeholder="说明变更原因（至少 2 字）" />
        </el-form-item>
        <el-form-item label="变更后预算" required>
          <el-table :data="changeForm.lines" size="small" max-height="320" stripe>
            <el-table-column prop="wbsName" label="任务" min-width="140" />
            <el-table-column label="材料" width="110">
              <template #default="{ row }">
                <el-input-number v-model="row.material" :min="0" :precision="2" size="small"
                  controls-position="right" style="width: 100px;" />
              </template>
            </el-table-column>
            <el-table-column label="外协" width="110">
              <template #default="{ row }">
                <el-input-number v-model="row.subcontract" :min="0" :precision="2" size="small"
                  controls-position="right" style="width: 100px;" />
              </template>
            </el-table-column>
            <el-table-column label="差旅" width="110">
              <template #default="{ row }">
                <el-input-number v-model="row.travel" :min="0" :precision="2" size="small"
                  controls-position="right" style="width: 100px;" />
              </template>
            </el-table-column>
            <el-table-column label="其他" width="110">
              <template #default="{ row }">
                <el-input-number v-model="row.other" :min="0" :precision="2" size="small"
                  controls-position="right" style="width: 100px;" />
              </template>
            </el-table-column>
          </el-table>
          <div class="hint">人工/间接仍按工时×费率与分摊率自动重算；审批通过后新预算替换基线</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changeVisible = false">取消</el-button>
        <el-button type="primary" :loading="changeSaving" @click="submitChange">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { projectList, projectDetail } from '@/api/pm/project'
import { budgetLines, budgetSaveLines, budgetSubmit, changeList, changeSubmit, rateList } from '@/api/pm/budget'
import ProjectFreezeBadge from '@/views/pm/cost/components/ProjectFreezeBadge.vue'

const route = useRoute()

const projects = ref([])
const projectId = ref('')
const project = ref({})
const lines = ref([])
const changes = ref([])
const rates = ref([])
const loading = ref(false)
const saving = ref(false)
const submitting = ref(false)
const readonly = ref(false)

const changeVisible = ref(false)
const changeSaving = ref(false)
const changeForm = ref({ reason: '', lines: [] })

onMounted(async () => {
  const res = await projectList({})
  projects.value = res.data || []
  try {
    const r = await rateList()
    rates.value = r.data || []
  } catch { rates.value = [] }
  const q = route.query.projectId
  if (q) {
    projectId.value = q
    onProjectChange()
  }
})

async function onProjectChange() {
  if (!projectId.value) return
  const res = await projectDetail(projectId.value)
  project.value = res.data || {}
  readonly.value = project.value.baselineLocked === '1' || project.value.status !== 'DRAFT'
  await loadLines()
  loadChanges()
}

async function loadLines() {
  loading.value = true
  try {
    const res = await budgetLines(projectId.value)
    lines.value = res.data?.lines || []
  } finally {
    loading.value = false
  }
}

async function loadChanges() {
  const res = await changeList({ projectId: projectId.value })
  changes.value = res.data || []
}

/** 前端近似重算（人工 = 工时×职级费率，间接 = 前五项×分摊率），保存后以后端为准 */
function recalcRow(row) {
  const rate = rateOfNum(row)
  const hours = Number(row.estHours) || 0
  row.labor = round2(hours * rate)
  const five = row.labor + (Number(row.material) || 0) + (Number(row.subcontract) || 0)
    + (Number(row.travel) || 0) + (Number(row.other) || 0)
  row.indirect = round2(five * (Number(project.value.fenplanRate) || 0.1))
}

function rateOf(row) {
  return rateOfNum(row).toFixed(0)
}
function rateOfNum(row) {
  const g = row.ownerGrade
  const hit = rates.value.find(r => r.grade === g)
  return hit ? Number(hit.ratePerHour) : 100
}
function lineTotal(row) {
  return round2((Number(row.labor) || 0) + (Number(row.material) || 0) + (Number(row.subcontract) || 0)
    + (Number(row.travel) || 0) + (Number(row.other) || 0) + (Number(row.indirect) || 0))
}
function round2(n) {
  return Math.round(n * 100) / 100
}

async function saveLines() {
  saving.value = true
  try {
    const payload = {
      lines: lines.value.map(r => ({
        wbsId: r.wbsId, material: r.material, subcontract: r.subcontract,
        travel: r.travel, other: r.other
      }))
    }
    await budgetSaveLines(projectId.value, payload)
    ElMessage.success('预算已保存（人工/间接已重算）')
    loadLines()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  } finally {
    saving.value = false
  }
}

async function submitBaseline() {
  submitting.value = true
  try {
    const res = await budgetSubmit(projectId.value)
    ElMessage.success(`已提交基线审批（基线金额 ${Number(res.data?.baselineAmount || 0).toLocaleString()}），等待 PMO 审批`)
    onProjectChange()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  } finally {
    submitting.value = false
  }
}

function openChange() {
  changeForm.value = {
    reason: '',
    lines: lines.value.map(r => ({
      wbsId: r.wbsId, wbsName: r.wbsName,
      material: r.material, subcontract: r.subcontract, travel: r.travel, other: r.other
    }))
  }
  changeVisible.value = true
}

async function submitChange() {
  const f = changeForm.value
  if (!f.reason || f.reason.trim().length < 2) { ElMessage.error('变更理由必填（至少 2 字）'); return }
  changeSaving.value = true
  try {
    await changeSubmit(projectId.value, { reason: f.reason, lines: f.lines })
    ElMessage.success('变更申请已提交（PMO → 财务双审批）')
    changeVisible.value = false
    loadChanges()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  } finally {
    changeSaving.value = false
  }
}

function summaryMethod({ columns, data }) {
  const sums = []
  columns.forEach((col, index) => {
    if (index === 0) { sums[0] = '合计'; return }
    const key = col.property
    if (['labor', 'material', 'subcontract', 'travel', 'other', 'indirect'].includes(key)) {
      const total = data.reduce((s, r) => s + (Number(r[key]) || 0), 0)
      sums[index] = fmt(total)
    } else if (key === 'total') {
      const total = data.reduce((s, r) => s + lineTotal(r), 0)
      sums[index] = fmt(total)
    } else {
      sums[index] = ''
    }
  })
  return sums
}

function fmt(v) {
  return v == null ? '—' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function statusType(s) {
  return s === 'ACTIVE' ? 'success' : (s === 'APPROVING' ? 'warning' : 'info')
}
function statusName(s) {
  return { DRAFT: '草稿', APPROVING: '审批中', ACTIVE: '已启动' }[s] || s
}
</script>

<style scoped>
.pm-container { padding: 4px; }
.pick-row { display: flex; align-items: center; gap: 10px; }
.label { color: #606266; }
.reject { color: #f56c6c; font-size: 12px; }
.right-actions { margin-left: auto; display: flex; gap: 8px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.hint { color: #909399; font-size: 12px; margin-top: 4px; }
</style>
