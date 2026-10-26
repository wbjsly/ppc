<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      title="在建工程台账（7.1.5）：CIP 项目 + 成本归集（工程款/安装费/资本化利息）→ 完工验收后发起在建转资"
      description="BR-4.13-08：归集 vs 已结算差额 ≠0 时转资须选「部分转资」并填说明；完工验收日期为转资超期预警基准（BR-4.13-06）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="项目编号 / 名称" clearable style="width:220px;"
          @keyup.enter="load" @clear="load" />
        <el-select v-model="status" placeholder="状态" clearable style="width:150px;" @change="load">
          <el-option label="在建中" value="OPEN" />
          <el-option label="已完工" value="COMPLETED" />
          <el-option label="已关闭" value="CLOSED" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canEdit" type="success" @click="openCreate">新建项目</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading"
        @row-click="onRowClick" highlight-current-row>
        <el-table-column prop="projectNo" label="项目编号" width="170">
          <template #default="{ row }"><b>{{ row.projectNo }}</b></template>
        </el-table-column>
        <el-table-column prop="name" label="项目名称" min-width="160" />
        <el-table-column label="预算金额" width="130" align="right">
          <template #default="{ row }">¥ {{ fmt(row.budgetAmount) }}</template>
        </el-table-column>
        <el-table-column label="已结算" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.settledAmount) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ OPEN: 'warning', COMPLETED: 'success', CLOSED: 'info' }[row.status]">
              {{ { OPEN: '在建中', COMPLETED: '已完工', CLOSED: '已关闭' }[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="completionDate" label="完工验收日" width="110" align="center" />
        <el-table-column v-if="canEdit" label="操作" width="180" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="openCost(row)">归集成本</el-button>
            <el-button v-if="row.status === 'OPEN'" link type="success" size="small"
              @click.stop="doComplete(row)">完工验收</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 项目详情：归集明细 -->
    <el-card shadow="never" style="margin-top:12px;" v-if="detail.project">
      <template #header>
        <span>归集明细 —— {{ detail.project.projectNo }} {{ detail.project.name }}</span>
      </template>
      <el-table :data="detail.costs || []" size="small" border>
        <el-table-column prop="costType" label="类别" width="120" align="center">
          <template #default="{ row }">{{ COST_TEXT[row.costType] }}</template>
        </el-table-column>
        <el-table-column label="金额" width="140" align="right">
          <template #default="{ row }">¥ {{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="costDate" label="发生日" width="110" align="center" />
        <el-table-column prop="sourceDocNo" label="来源单号" width="140" />
        <el-table-column prop="description" label="说明" min-width="160" />
      </el-table>
      <el-descriptions :column="3" size="small" border style="margin-top:10px;">
        <el-descriptions-item label="归集合计">¥ {{ fmt(detail.costTotal) }}</el-descriptions-item>
        <el-descriptions-item label="已结算">¥ {{ fmt(detail.project.settledAmount) }}</el-descriptions-item>
        <el-descriptions-item label="差额（BR-08）">
          <span :style="{ color: diff(detail) !== 0 ? '#F56C6C' : '#67C23A' }">
            ¥ {{ fmt(diff(detail)) }}
          </span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 新建项目 -->
    <el-dialog v-model="createVisible" title="新建在建工程项目" width="480px">
      <el-form label-width="100px" size="small">
        <el-form-item label="项目名称" required>
          <el-input v-model="createForm.name" />
        </el-form-item>
        <el-form-item label="预算金额">
          <el-input-number v-model="createForm.budgetAmount" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="createForm.ownerName" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 归集成本 -->
    <el-dialog v-model="costVisible" :title="`归集成本 —— ${costTarget.projectNo || ''}`" width="460px">
      <el-form label-width="90px" size="small">
        <el-form-item label="归集类别" required>
          <el-select v-model="costForm.costType" style="width:100%;">
            <el-option label="工程款" value="ENGINGEE" />
            <el-option label="安装费" value="INSTALL" />
            <el-option label="资本化利息" value="INTEREST" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input-number v-model="costForm.amount" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="发生日期">
          <el-date-picker v-model="costForm.costDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="来源单号">
          <el-input v-model="costForm.sourceDocNo" placeholder="可选：结算单/发票号" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="costForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="costVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doAddCost">归集</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getCipPageApi, createCipApi, getCipDetailApi, addCipCostApi, completeCipApi
} from '@/api/eam/asset'

const userStore = useUserStore()
const COST_TEXT = { ENGINGEE: '工程款', INSTALL: '安装费', INTEREST: '资本化利息' }

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const keyword = ref('')
const status = ref('')
const filters = reactive({ current: 1, size: 20 })

const detail = reactive({ project: null, costs: [], costTotal: 0 })

const createVisible = ref(false)
const createForm = reactive({ name: '', budgetAmount: null, ownerName: '', remark: '' })

const costVisible = ref(false)
const costTarget = reactive({ id: '', projectNo: '' })
const costForm = reactive({ costType: 'ENGINGEE', amount: null, costDate: '', sourceDocNo: '', description: '' })

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canEdit = computed(() => myRoles.value.some(r =>
  ['ROLE_ADMIN', 'ROLE_ASSET_ADMIN'].includes(r)))

function fmt(v) { return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }
function diff(d) {
  const settled = Number(d.project && d.project.settledAmount || 0)
  return (Number(d.costTotal) - settled).toFixed(2)
}

async function load() {
  loading.value = true
  try {
    const res = await getCipPageApi({ ...filters, keyword: keyword.value, status: status.value })
    rows.value = (res.data && res.data.records) || []
  } finally {
    loading.value = false
  }
}

async function onRowClick(row) {
  const res = await getCipDetailApi(row.id)
  Object.assign(detail, res.data || {})
}

function openCreate() {
  Object.assign(createForm, { name: '', budgetAmount: null, ownerName: '', remark: '' })
  createVisible.value = true
}

async function doCreate() {
  saving.value = true
  try {
    await createCipApi({ ...createForm })
    ElMessage.success('项目已创建')
    createVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

function openCost(row) {
  costTarget.id = row.id
  costTarget.projectNo = row.projectNo
  Object.assign(costForm, { costType: 'ENGINGEE', amount: null, costDate: '', sourceDocNo: '', description: '' })
  costVisible.value = true
}

async function doAddCost() {
  saving.value = true
  try {
    await addCipCostApi({ ...costForm, projectId: costTarget.id })
    ElMessage.success('已归集')
    costVisible.value = false
    onRowClick({ id: costTarget.id })
    load()
  } finally {
    saving.value = false
  }
}

async function doComplete(row) {
  await ElMessageBox.confirm(`确认项目 ${row.projectNo} 完工验收？验收日期将作为转资超期预警基准。`, '完工验收', { type: 'warning' })
  await completeCipApi(row.id)
  ElMessage.success('已完工验收')
  load()
}

onMounted(load)
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.spacer { flex: 1; }
</style>
