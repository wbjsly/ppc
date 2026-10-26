<template>
  <div class="change-approve">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        项目变更审批与执行跟踪（FR-4.8-8-3/5，C-4.8-10）：三级动态路由走统一审批中心
        （≤5% 且 ≤5 天 PMO 单签 / &gt;5 天或 5–10% PMO+财务双签 / &gt;10% 或涉合同 分管领导+PMO 双签）。
        审批通过 → 执行中；PM 确认落地 → 已完成；PMO 核验 → 已验证（偏差&gt;50% L4 升级通知 PMO）。
        涉合同变更审批通过时自动联动销售域《合同变更单》（BR-4.8-33）。
        审批待办走统一审批中心。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <div class="toolbar">
        <el-select v-model="filter.projectId" placeholder="按项目筛选" filterable clearable
          style="width: 280px;" @change="load">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-select v-model="filter.status" placeholder="按状态筛选" clearable style="width: 160px;" @change="load">
          <el-option value="EXECUTING" label="执行中" />
          <el-option value="DONE" label="已完成" />
          <el-option value="VERIFIED" label="已验证" />
          <el-option value="REJECTED" label="已驳回" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header><b>变更台账</b></template>
      <el-table :data="changes" v-loading="loading" stripe>
        <el-table-column prop="change.changeNo" label="变更单号" width="150" />
        <el-table-column prop="projectName" label="项目" min-width="150" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ typeName(row.change.changeType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="change.reason" label="原因" width="90" />
        <el-table-column prop="change.budgetAdd" label="追加金额" width="100" :precision="2" />
        <el-table-column prop="change.budgetRate" label="预算影响(%)" width="110" />
        <el-table-column label="涉合同" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.change.isContractFlag === '1'" type="warning" size="small">是</el-tag>
            <span v-else>否</span>
          </template>
        </el-table-column>
        <el-table-column prop="change.verNoLabel" label="基线版本" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.change.status)">{{ statusName(row.change.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.change.status === 'EXECUTING'" link type="primary"
              @click="doAdvance(row)">确认落地</el-button>
            <el-button v-if="row.change.status === 'DONE'" link type="success"
              @click="openVerify(row)">核验</el-button>
            <el-button link type="info" @click="openVersions(row)">版本历史</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !changes.length" description="暂无变更单" />
    </el-card>

    <el-card>
      <template #header><b>台账统计</b></template>
      <el-row :gutter="16" v-if="stats">
        <el-col :span="4"><div class="stat"><div class="num">{{ stats.total }}</div><div class="lbl">总变更数</div></div></el-col>
        <el-col :span="4"><div class="stat"><div class="num">{{ stats.contractCount }}</div><div class="lbl">涉合同</div></div></el-col>
        <el-col :span="16">
          <div class="stat-group">
            <span class="lbl">按类型：</span>
            <el-tag v-for="(v, k) in stats.byType" :key="k" size="small" style="margin-right:6px;">
              {{ typeName(k) }} × {{ v }}
            </el-tag>
          </div>
          <div class="stat-group">
            <span class="lbl">按状态：</span>
            <el-tag v-for="(v, k) in stats.byStatus" :key="k" size="small" type="info" style="margin-right:6px;">
              {{ statusName(k) }} × {{ v }}
            </el-tag>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 核验对话框 -->
    <el-dialog v-model="verifyVisible" title="PMO 核验（实际 vs 预估）" width="520px">
      <el-alert v-if="verifyRow" type="info" :closable="false" style="margin-bottom: 12px;"
        :title="`变更单：${verifyRow.change.changeNo}　预估追加 ${verifyRow.change.budgetAdd} 元`" />
      <el-form label-width="110px" size="default">
        <el-form-item label="核验说明">
          <el-input v-model="verifyForm.opinion" type="textarea" :rows="2"
            placeholder="实际影响与预估影响的偏差原因（纳入项目经验库）" />
        </el-form-item>
        <el-form-item label="实际偏差(%)">
          <el-input-number v-model="verifyForm.verifyDeviation" :min="0" :precision="2" style="width: 200px;" />
          <span class="field-hint">&gt;50% 系统 L4 提示复盘并升级通知 PMO</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="verifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doVerify">完成核验</el-button>
      </template>
    </el-dialog>

    <!-- 版本历史对话框 -->
    <el-dialog v-model="versionsVisible" title="基线版本历史（BR-4.8-31 永久只读）" width="720px">
      <el-table :data="versions" v-loading="versionsLoading" stripe size="small">
        <el-table-column prop="verNoLabel" label="版本号" width="90" />
        <el-table-column prop="baselineAmount" label="基线总额" width="120" :precision="2" />
        <el-table-column label="当前生效" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.currentFlag === '1'" type="success" size="small">当前</el-tag>
            <span v-else>历史</span>
          </template>
        </el-table-column>
        <el-table-column prop="changeId" label="来源变更" min-width="140" show-overflow-tooltip />
        <el-table-column prop="createDate" label="生成时间" width="160" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openSnapshot(row)">查看快照</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 单版本快照对话框 -->
    <el-dialog v-model="snapshotVisible" :title="`基线快照 ${snapshotVer}`" width="720px">
      <el-table :data="snapshotLines" v-loading="snapshotLoading" stripe size="small">
        <el-table-column prop="wbsId" label="WBS 任务" min-width="140" show-overflow-tooltip />
        <el-table-column prop="labor" label="人工" width="90" :precision="2" />
        <el-table-column prop="material" label="材料" width="90" :precision="2" />
        <el-table-column prop="subcontract" label="外协" width="90" :precision="2" />
        <el-table-column prop="travel" label="差旅" width="90" :precision="2" />
        <el-table-column prop="other" label="其他" width="90" :precision="2" />
        <el-table-column prop="indirect" label="间接" width="90" :precision="2" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/project-change'
import { projectList } from '@/api/pm/project'

const loading = ref(false)
const projects = ref([])
const changes = ref([])
const stats = ref(null)
const filter = ref({ projectId: '', status: '' })
const submitting = ref(false)

const verifyVisible = ref(false)
const verifyRow = ref(null)
const verifyForm = ref({ opinion: '', verifyDeviation: 0 })

const versionsVisible = ref(false)
const versionsLoading = ref(false)
const versions = ref([])
const versionsProjectId = ref('')

const snapshotVisible = ref(false)
const snapshotLoading = ref(false)
const snapshotVer = ref('')
const snapshotLines = ref([])

const typeName = t => ({ SCOPE: '范围', SCHEDULE: '进度', BUDGET: '预算', RESOURCE: '资源', COMBO: '组合' }[t] || t)
const statusName = s => ({ EXECUTING: '执行中', DONE: '已完成', VERIFIED: '已验证', REJECTED: '已驳回', DRAFT: '草稿', APPROVING: '审批中' }[s] || s)
const statusType = s => ({ EXECUTING: 'warning', DONE: 'primary', VERIFIED: 'success', REJECTED: 'danger' }[s] || 'info')

async function load() {
  loading.value = true
  try {
    const [c, s] = await Promise.all([
      api.changeList(filter.value),
      api.changeStats(filter.value.projectId || undefined)
    ])
    changes.value = c.data || []
    stats.value = s.data
  } finally {
    loading.value = false
  }
}

async function loadProjects() {
  const { data } = await projectList({})
  projects.value = (data || []).map(p => p.project || p)
}

async function doAdvance(row) {
  const { data } = await api.advanceChange(row.change.id, { opinion: '变更已落地' })
  ElMessage.success(`已确认落地：${row.change.changeNo}（${data.status}）`)
  load()
}

function openVerify(row) {
  verifyRow.value = row
  verifyForm.value = { opinion: '', verifyDeviation: 0 }
  verifyVisible.value = true
}

async function doVerify() {
  submitting.value = true
  try {
    const { data } = await api.verifyChange(verifyRow.value.change.id, verifyForm.value)
    let msg = `已核验：${verifyRow.value.change.changeNo}（${data.status}）`
    if (data.l4Warning) msg += `（${data.l4Warning}）`
    ElMessage.warning(msg)
    verifyVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function openVersions(row) {
  versionsProjectId.value = row.change.projectId
  versionsVisible.value = true
  versionsLoading.value = true
  try {
    const { data } = await api.baselineVersions(row.change.projectId)
    versions.value = data || []
  } finally {
    versionsLoading.value = false
  }
}

async function openSnapshot(row) {
  snapshotVer.value = row.verNoLabel
  snapshotVisible.value = true
  snapshotLoading.value = true
  try {
    const { data } = await api.baselineSnapshot(versionsProjectId.value, row.verNoLabel)
    snapshotLines.value = data.lines || []
  } finally {
    snapshotLoading.value = false
  }
}

onMounted(() => {
  loadProjects()
  load()
})
</script>

<style scoped>
.hint { color: #606266; font-size: 12px; line-height: 1.8; }
.toolbar { display: flex; gap: 10px; align-items: center; }
.field-hint { margin-left: 10px; color: #909399; font-size: 12px; }
.stat { text-align: center; }
.stat .num { font-size: 22px; font-weight: 600; }
.stat .lbl { color: #909399; font-size: 12px; margin-top: 4px; }
.stat-group { margin-bottom: 8px; }
.stat-group .lbl { color: #606266; font-size: 12px; }
</style>
