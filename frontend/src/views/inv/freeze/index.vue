<template>
  <div class="page">
    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="类型">
        <el-select v-model="query.freezeType" clearable placeholder="全部" style="width: 120px">
          <el-option label="质量冻结" value="QUALITY" />
          <el-option label="财务冻结" value="FINANCE" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 130px">
          <el-option label="审批中" value="PENDING" />
          <el-option label="生效中" value="ACTIVE" />
          <el-option label="已解冻" value="RELEASED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
      </el-form-item>
      <el-form-item label="物料">
        <el-input v-model="query.itemCode" placeholder="物料编码" clearable style="width: 140px" />
      </el-form-item>
      <el-form-item label="批次">
        <el-input v-model="query.batchNo" placeholder="批次号" clearable style="width: 130px" />
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" placeholder="单号/物料/原因" clearable style="width: 150px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">查询</el-button>
        <el-button v-if="canInitiate" type="danger" plain @click="openApply">发起冻结</el-button>
      </el-form-item>
      <span class="tip">审批签署请前往「质量管理-质量协同-审批中心」（按角色待办，C-4.4-05 跨类型不可签）</span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="freezeNo" label="冻结单号" width="150" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.freezeType === 'QUALITY' ? 'warning' : 'danger'" size="small">
            {{ row.freezeType === 'QUALITY' ? '质量' : '财务' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="itemCode" label="物料" width="130" />
      <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="batchNo" label="批次" width="120">
        <template #default="{ row }">{{ row.batchNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="qty" label="数量" width="90" align="right" />
      <el-table-column prop="reason" label="原因" min-width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.source === 'NCR'" type="info" size="small">NCR</el-tag>
          <span v-else>手工</span>
        </template>
      </el-table-column>
      <el-table-column prop="applyBy" label="发起人" width="110" />
      <el-table-column prop="createDate" label="时间" width="155">
        <template #default="{ row }">{{ fmtTime(row.createDate) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <!-- NCR 来源只读（解冻走 NCR 流程）；解冻仅原发起人生效行 -->
          <el-button v-if="row.canUnfreeze" link type="primary" size="small"
            @click="openUnfreeze(row)">解冻</el-button>
          <span v-else-if="row.readonly" class="readonly-tip">NCR 流程</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 发起冻结对话框 -->
    <el-dialog v-model="applyVisible" title="发起冻结" width="560px">
      <el-form ref="applyRef" :model="applyForm" :rules="applyRules" label-width="96px">
        <el-form-item label="冻结类型" prop="freezeType">
          <el-radio-group v-model="applyForm.freezeType">
            <el-radio v-if="canQuality" label="QUALITY">质量冻结</el-radio>
            <el-radio v-if="canFinance" label="FINANCE">财务冻结</el-radio>
          </el-radio-group>
          <div class="form-tip">单笔操作只能选择一种类型（C-4.4-05）；原因必填</div>
        </el-form-item>
        <el-form-item label="仓库" prop="warehouseCode">
          <el-select v-model="applyForm.warehouseCode" filterable placeholder="选择仓库"
            style="width: 100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="`${w.whCode} ${w.whName}`"
              :value="w.whCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="物料编码" prop="itemCode">
          <el-input v-model="applyForm.itemCode" placeholder="如 RM00010001" />
        </el-form-item>
        <el-form-item label="批次" prop="batchNo">
          <el-input v-model="applyForm.batchNo" placeholder="无批次物料填空串" />
        </el-form-item>
        <el-form-item label="冻结数量" prop="qty">
          <el-input-number v-model="applyForm.qty" :min="0.0001" :precision="4" style="width: 180px" />
        </el-form-item>
        <el-form-item label="冻结原因" prop="reason">
          <el-input v-model="applyForm.reason" type="textarea" :rows="2" maxlength="500"
            placeholder="质量问题隔离 / 审计封存 / 法律纠纷等（必填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitApply">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- 发起解冻对话框 -->
    <el-dialog v-model="unfreezeVisible" title="发起解冻" width="560px">
      <el-alert v-if="current" :title="`冻结单 ${current.freezeNo}（${current.itemCode} / ${current.batchNo || '-'}）`"
        type="info" :closable="false" style="margin-bottom: 12px" />
      <el-form ref="unfreezeRef" :model="unfreezeForm" :rules="unfreezeRules" label-width="110px">
        <el-form-item label="处理结果" prop="releaseResult">
          <el-input v-model="unfreezeForm.releaseResult" type="textarea" :rows="2" maxlength="500"
            placeholder="问题处理结果（必填）" />
        </el-form-item>
        <el-form-item label="解冻依据" prop="releaseBasis">
          <el-input v-model="unfreezeForm.releaseBasis" type="textarea" :rows="2" maxlength="500"
            placeholder="复检报告 / 审计结论等依据（必填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="unfreezeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitUnfreeze">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getFreezesApi, applyFreezeApi, applyUnfreezeApi } from '@/api/inv/freeze'
import { getWarehousesApi } from '@/api/inv/warehouse'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const warehouses = ref([])
const query = ref({ freezeType: '', status: '', itemCode: '', batchNo: '', keyword: '' })

const applyVisible = ref(false)
const unfreezeVisible = ref(false)
const current = ref(null)
const applyRef = ref(null)
const unfreezeRef = ref(null)

const applyForm = ref({})
const unfreezeForm = ref({ releaseResult: '', releaseBasis: '' })

// 角色显隐（接口层二次校验兜底，4.3.2 权限口径）
// 注意：user-info 的 permissions 恒为空数组，角色在 roles 字段
const perms = computed(() => userStore.userInfo?.roles || [])
const isAdmin = computed(() => perms.value.includes('ROLE_ADMIN'))
const canQuality = computed(() => isAdmin.value || perms.value.includes('ROLE_QUALITY_ENG'))
const canFinance = computed(() => isAdmin.value || perms.value.includes('ROLE_FINANCE'))
const canInitiate = computed(() => canQuality.value || canFinance.value)

const applyRules = {
  freezeType: [{ required: true, message: '请选择冻结类型', trigger: 'change' }],
  warehouseCode: [{ required: true, message: '请选择仓库', trigger: 'change' }],
  itemCode: [{ required: true, message: '请输入物料编码', trigger: 'blur' }],
  batchNo: [{ required: true, message: '请输入批次（无批次填空串）', trigger: 'blur' }],
  qty: [{ required: true, message: '请输入冻结数量', trigger: 'change' }],
  reason: [{ required: true, message: '冻结原因必填', trigger: 'blur' }]
}

const unfreezeRules = {
  releaseResult: [{ required: true, message: '请填写处理结果', trigger: 'blur' }],
  releaseBasis: [{ required: true, message: '请填写解冻依据', trigger: 'blur' }]
}

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

function statusText(s) {
  return { PENDING: '审批中', ACTIVE: '生效中', RELEASED: '已解冻', REJECTED: '已驳回' }[s] || s
}

function statusTag(s) {
  return { PENDING: 'info', ACTIVE: 'danger', RELEASED: 'success', REJECTED: 'info' }[s] || 'info'
}

async function loadData() {
  loading.value = true
  try {
    const params = {}
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    const res = await getFreezesApi(params)
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openApply() {
  // 默认给首个可用类型
  applyForm.value = {
    freezeType: canQuality.value ? 'QUALITY' : 'FINANCE',
    warehouseCode: '', itemCode: '', batchNo: '', qty: null, reason: '',
    scope: 'BATCH'
  }
  applyVisible.value = true
  requestAnimationFrame(() => applyRef.value?.clearValidate())
}

async function submitApply() {
  const ok = await applyRef.value.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    await applyFreezeApi({ ...applyForm.value })
    ElMessage.success('冻结申请已提交，待对应主管审批')
    applyVisible.value = false
    loadData()
  } finally {
    saving.value = false
  }
}

function openUnfreeze(row) {
  current.value = row
  unfreezeForm.value = { releaseResult: '', releaseBasis: '' }
  unfreezeVisible.value = true
  requestAnimationFrame(() => unfreezeRef.value?.clearValidate())
}

async function submitUnfreeze() {
  const ok = await unfreezeRef.value.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    await applyUnfreezeApi(current.value.id, { ...unfreezeForm.value })
    ElMessage.success('解冻申请已提交，待对应主管审批')
    unfreezeVisible.value = false
    loadData()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  loadData()
  try {
    const res = await getWarehousesApi({ page: 1, size: 200 })
    warehouses.value = res.data?.records || res.data?.rows || res.data || []
  } catch {
    warehouses.value = []
  }
})
</script>

<style scoped>
.page { padding: 4px; }
.filter-bar { margin-bottom: 4px; }
.tip { color: #909399; font-size: 12px; line-height: 32px; margin-left: 8px; }
.form-tip { color: #909399; font-size: 12px; line-height: 18px; }
.readonly-tip { color: #909399; font-size: 12px; }
</style>
