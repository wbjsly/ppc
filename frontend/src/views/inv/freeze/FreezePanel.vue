<template>
  <div class="page">
    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item v-if="!lockedType" label="类型">
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
        <el-button v-if="canInitiate" :type="freezeType === 'FINANCE' ? 'danger' : 'warning'"
          plain @click="openApply">发起{{ typeLabel }}冻结</el-button>
      </el-form-item>
      <span class="tip">审批签署前往「审批中心」（C-4.4-05 跨类型不可签）</span>
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
      <el-table-column label="范围" width="90">
        <template #default="{ row }">{{ scopeText(row.scope) }}</template>
      </el-table-column>
      <el-table-column prop="itemCode" label="物料" width="130" />
      <el-table-column prop="itemName" label="名称" min-width="110" show-overflow-tooltip />
      <el-table-column prop="batchNo" label="批次" width="120">
        <template #default="{ row }">{{ row.batchNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="qty" label="数量" width="90" align="right" />
      <el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.source === 'NCR'" type="info" size="small">NCR</el-tag>
          <span v-else-if="row.source === 'REVIEW'" class="readonly-tip">复核</span>
          <span v-else>手工</span>
        </template>
      </el-table-column>
      <el-table-column prop="applyBy" label="发起人" width="110" />
      <el-table-column prop="createDate" label="时间" width="155">
        <template #default="{ row }">{{ fmtTime(row.createDate) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openImpact(row)">影响</el-button>
          <!-- NCR/复核来源只读（解冻走原流程）；解冻仅原发起人生效行 -->
          <el-button v-if="row.canUnfreeze" link type="primary" size="small"
            @click="openUnfreeze(row)">解冻</el-button>
          <span v-else-if="row.readonly" class="readonly-tip">NCR 流程</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 发起冻结对话框 -->
    <el-dialog v-model="applyVisible" :title="`发起${typeLabel}冻结`" width="580px">
      <el-form ref="applyRef" :model="applyForm" :rules="applyRules" label-width="96px">
        <el-form-item v-if="!lockedType" label="冻结类型" prop="freezeType">
          <el-radio-group v-model="applyForm.freezeType">
            <el-radio label="QUALITY">质量冻结</el-radio>
            <el-radio label="FINANCE">财务冻结</el-radio>
          </el-radio-group>
          <div class="form-tip">单笔操作只能选择一种类型（C-4.4-05）；原因必填</div>
        </el-form-item>
        <el-form-item label="影响范围" prop="scope">
          <el-radio-group v-model="applyForm.scope">
            <el-radio label="ALL">全部库存</el-radio>
            <el-radio label="BATCH">指定批次</el-radio>
            <el-radio label="BIN">指定仓位</el-radio>
          </el-radio-group>
          <div class="form-tip">全部库存与指定批次/仓位共用批次维度；仓位粒度由执行按 FIFO 拆行</div>
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
        <el-form-item v-if="applyForm.scope !== 'ALL'" label="批次" prop="batchNo">
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

    <!-- 影响评估二次确认（FR-4.4-5-3 异常列） -->
    <el-dialog v-model="confirmVisible" title="冻结影响评估" width="520px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
        :title="`该冻结触发影响评估（范围：${scopeText(applyForm.scope)}，阈值 ${estimate.ratioPct || 50}%）`" />
      <el-descriptions :column="1" border>
        <el-descriptions-item label="维度可用量">{{ estimate.availableQty }}</el-descriptions-item>
        <el-descriptions-item label="受影响可用量">{{ estimate.affectedQty }}</el-descriptions-item>
        <el-descriptions-item label="关联 SO 数">{{ estimate.affectedSoCount }}</el-descriptions-item>
      </el-descriptions>
      <div class="form-tip" style="margin-top: 8px">确认后提交审批；取消返回修改。</div>
      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doSubmit">确认提交</el-button>
      </template>
    </el-dialog>

    <!-- 影响详情（4.9.4 同构抽屉，行内快捷查看） -->
    <el-drawer v-model="impactVisible" :title="`影响详情 ${current?.freezeNo || ''}`" size="560px">
      <template v-if="current && impact">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px"
          :title="`冻结 ${current.itemCode} / ${current.batchNo || '全部批次'} × ${current.qty}`" />
        <h4>受影响 SO <el-tag size="mini" type="success">精确到批次</el-tag></h4>
        <el-table v-if="impact.sos?.length" :data="impact.sos" size="small" border>
          <el-table-column prop="soNo" label="订单号" />
          <el-table-column prop="lineNo" label="行号" width="70" />
          <el-table-column prop="qty" label="预留量" width="90" align="right" />
        </el-table>
        <p v-else class="readonly-tip">无受影响订单</p>

        <h4 style="margin-top: 14px">受影响 PO <el-tag size="mini" type="warning">物料级</el-tag></h4>
        <el-table v-if="impact.pos?.length" :data="impact.pos" size="small" border>
          <el-table-column prop="poNo" label="采购单号" />
          <el-table-column prop="lineNo" label="行号" width="70" />
          <el-table-column prop="qty" label="未清量" width="90" align="right" />
        </el-table>
        <p v-else class="readonly-tip">无未清 PO</p>

        <h4 style="margin-top: 14px">受影响工单
          <el-tag size="mini" type="info">数据源未落地</el-tag></h4>
        <p class="readonly-tip">生产工单域未落地（偏差 D1），清单暂不可用。</p>
      </template>
      <template #footer>
        <el-button @click="impactVisible = false">关闭</el-button>
      </template>
    </el-drawer>

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
import { getFreezesApi, applyFreezeApi, applyUnfreezeApi, estimateFreezeApi } from '@/api/inv/freeze'
import { getWarehousesApi } from '@/api/inv/warehouse'
import { useUserStore } from '@/store/user'

// freezeType：'QUALITY' / 'FINANCE' / ''（空 = 混合总览，4.3.2）
const props = defineProps({
  freezeType: { type: String, default: '' }
})

const userStore = useUserStore()

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const warehouses = ref([])
const query = ref({ freezeType: '', status: '', itemCode: '', batchNo: '', keyword: '' })

const applyVisible = ref(false)
const confirmVisible = ref(false)
const unfreezeVisible = ref(false)
const impactVisible = ref(false)
const current = ref(null)
const impact = ref(null)
const applyRef = ref(null)
const unfreezeRef = ref(null)

const applyForm = ref({})
const unfreezeForm = ref({ releaseResult: '', releaseBasis: '' })
const estimate = ref({})

const typeLabel = computed(() => props.freezeType === 'QUALITY' ? '质量'
  : props.freezeType === 'FINANCE' ? '财务' : '')
const lockedType = computed(() => !!props.freezeType)

// 角色显隐（接口层二次校验兜底，4.3.2 权限口径）
// 注意：user-info 的 permissions 恒为空数组，角色在 roles 字段
const perms = computed(() => userStore.userInfo?.roles || [])
const isAdmin = computed(() => perms.value.includes('ROLE_ADMIN'))
const canQuality = computed(() => isAdmin.value || perms.value.includes('ROLE_QUALITY_ENG'))
const canFinance = computed(() => isAdmin.value || perms.value.includes('ROLE_FINANCE'))
const canInitiate = computed(() => {
  if (props.freezeType === 'QUALITY') return canQuality.value
  if (props.freezeType === 'FINANCE') return canFinance.value
  return canQuality.value || canFinance.value
})

const applyRules = {
  freezeType: [{ required: true, message: '请选择冻结类型', trigger: 'change' }],
  scope: [{ required: true, message: '请选择影响范围', trigger: 'change' }],
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

function scopeText(s) {
  return { ALL: '全部库存', BATCH: '指定批次', BIN: '指定仓位' }[s] || s || '-'
}

async function loadData() {
  loading.value = true
  try {
    const params = {}
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    if (props.freezeType) params.freezeType = props.freezeType
    const res = await getFreezesApi(params)
    rows.value = res.data || []
  } catch (e) {
    console.warn('[freeze] load failed', e)
  } finally {
    loading.value = false
  }
}

function openApply() {
  applyForm.value = {
    freezeType: props.freezeType || (canQuality.value ? 'QUALITY' : 'FINANCE'),
    scope: 'BATCH',
    warehouseCode: '', itemCode: '', batchNo: '', qty: null, reason: ''
  }
  applyVisible.value = true
  requestAnimationFrame(() => applyRef.value?.clearValidate())
}

// FR-4.4-5-3：scope=ALL 或占比 ≥ 阈值 → 先预估再二次确认，否则直接提交
async function submitApply() {
  const ok = await applyRef.value.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    const res = await estimateFreezeApi({
      warehouseCode: applyForm.value.warehouseCode,
      itemCode: applyForm.value.itemCode,
      batchNo: applyForm.value.scope === 'ALL' ? '' : applyForm.value.batchNo,
      scope: applyForm.value.scope,
      qty: applyForm.value.qty
    })
    estimate.value = res.data || {}
    if (estimate.value.needConfirm) {
      applyVisible.value = false
      confirmVisible.value = true
      return
    }
    await doSubmit()
  } catch (e) {
    console.warn('[freeze] estimate failed', e)
  } finally {
    saving.value = false
  }
}

async function doSubmit() {
  saving.value = true
  try {
    await applyFreezeApi({ ...applyForm.value })
    ElMessage.success('冻结申请已提交，待对应主管审批')
    confirmVisible.value = false
    applyVisible.value = false
    loadData()
  } catch (e) {
    console.warn('[freeze] apply failed', e)
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
  } catch (e) {
    console.warn('[freeze] unfreeze failed', e)
  } finally {
    saving.value = false
  }
}

function openImpact(row) {
  current.value = row
  impact.value = null
  impactVisible.value = true
  try {
    impact.value = row.impactJson ? JSON.parse(row.impactJson) : null
  } catch {
    impact.value = null
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
h4 { margin: 6px 0; display: flex; align-items: center; gap: 6px; }
</style>
