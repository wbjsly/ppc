<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      :title="mode === 'PURCHASE' ? '采购转资（7.1.1）：入库过账+质检合格的采购订单 → 转资建卡' : '在建转资（7.1.2）：完工验收的 CIP 项目 → 转资建卡'"
      description="BR-4.13-07 原值与发票/归集超差阻断审核；BR-4.13-09 低于资本化下限阻断建卡；一物一卡拆分；审核通过生成卡片+转资凭证（借 固定资产 / 贷 在建工程或应付暂估）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="单号 / 资产名 / 来源单" clearable style="width:220px;"
          @keyup.enter="load" @clear="load" />
        <el-select v-model="status" placeholder="状态" clearable style="width:150px;" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审批" value="PENDING_APPROVE" />
          <el-option label="已完成" value="COMPLETED" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canEdit" type="success" @click="openPending">发起转资</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="capNo" label="转资单号" width="160">
          <template #default="{ row }"><b>{{ row.capNo }}</b></template>
        </el-table-column>
        <el-table-column prop="assetName" label="资产名称" min-width="140" />
        <el-table-column prop="sourceDocNo" label="来源单据" width="150" />
        <el-table-column prop="qty" label="数量" width="70" align="center" />
        <el-table-column label="原值合计" width="130" align="right">
          <template #default="{ row }">¥ {{ fmt(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column prop="categoryCode" label="分类" width="90" align="center" />
        <el-table-column prop="effectiveDate" label="启用日期" width="110" align="center" />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ DRAFT: 'info', PENDING_APPROVE: 'warning', COMPLETED: 'success' }[row.status]">
              {{ { DRAFT: '草稿', PENDING_APPROVE: '待审批', COMPLETED: '已完成' }[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canEdit && row.status === 'DRAFT'" link type="primary" size="small"
              @click="doSubmit(row)">提交审核</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情：折旧预览 -->
    <el-card shadow="never" style="margin-top:12px;" v-if="detail.cap">
      <template #header>
        <span>转资详情 —— {{ detail.cap.capNo }}</span>
      </template>
      <el-descriptions :column="3" size="small" border>
        <el-descriptions-item label="转资单号">{{ detail.cap.capNo }}</el-descriptions-item>
        <el-descriptions-item label="模式">{{ detail.cap.mode === 'PURCHASE' ? '采购转资' : '在建转资' }}</el-descriptions-item>
        <el-descriptions-item label="来源单据">{{ detail.cap.sourceDocNo }}</el-descriptions-item>
        <el-descriptions-item label="资产名称">{{ detail.cap.assetName }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ detail.cap.qty }} 件</el-descriptions-item>
        <el-descriptions-item label="原值合计">¥ {{ fmt(detail.cap.totalAmount) }}</el-descriptions-item>
        <el-descriptions-item label="使用年限">{{ detail.cap.lifeYears }} 年</el-descriptions-item>
        <el-descriptions-item label="残值率">{{ pct(detail.cap.residualRate) }}</el-descriptions-item>
        <el-descriptions-item label="启用日期">{{ detail.cap.effectiveDate }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.cap.rejectReason" label="驳回原因" :span="3">
          <span style="color:#F56C6C;">{{ detail.cap.rejectReason }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-descriptions v-if="preview" :column="3" size="small" border style="margin-top:10px;"
        title="折旧预览（不落计划表，仅审核参考）">
        <el-descriptions-item label="残值">¥ {{ fmt(preview.residual) }}</el-descriptions-item>
        <el-descriptions-item label="月折旧额">
          <b style="color:#409EFF;">¥ {{ fmt(preview.monthlyDepreciation) }}</b>
        </el-descriptions-item>
        <el-descriptions-item label="折旧方法">{{ preview.method === 'STRAIGHT_LINE' ? '直线法' : preview.method }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 待转资清单（发起入口）-->
    <el-dialog v-model="pendingVisible" title="待转资清单（超期分级预警）" width="760px">
      <el-table :data="pendingRows" size="small" border v-loading="pendingLoading" max-height="420">
        <el-table-column prop="mode" label="模式" width="90" align="center">
          <template #default="{ row }">{{ row.mode === 'PURCHASE' ? '采购' : '在建' }}</template>
        </el-table-column>
        <el-table-column prop="sourceDocNo" label="来源单号" width="160" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="acceptanceDate" label="验收合格日" width="110" align="center" />
        <el-table-column label="超期天数" width="90" align="center">
          <template #default="{ row }">
            <span :style="{ color: row.level !== 'NORMAL' ? '#F56C6C' : '#67C23A' }">
              {{ row.overdueDays }} 天
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="level" label="预警" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ NORMAL: 'success', WARNED: 'warning', ESCALATED: 'danger' }[row.level]">
              {{ { NORMAL: '正常', WARNED: '预警', ESCALATED: '升级' }[row.level] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="success" size="small" @click="startCap(row)">发起转资</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 转资申请表单 -->
    <el-dialog v-model="formVisible" :title="`发起${mode === 'PURCHASE' ? '采购' : '在建'}转资`" width="540px">
      <el-form label-width="110px" size="small">
        <el-form-item label="来源单据">
          <el-input :model-value="form.sourceDocNo" disabled />
        </el-form-item>
        <el-form-item label="资产名称" required>
          <el-input v-model="form.assetName" />
        </el-form-item>
        <el-form-item label="规格型号">
          <el-input v-model="form.specModel" />
        </el-form-item>
        <el-form-item label="资产分类" required>
          <el-select v-model="form.categoryCode" style="width:100%;" @change="onCategoryChange">
            <el-option v-for="c in categories" :key="c.categoryCode" :label="c.name" :value="c.categoryCode" />
          </el-select>
          <div class="hint">带入默认折旧政策，年限偏离默认值须填调整原因</div>
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="form.qty" :min="1" :max="500" />
          <div class="hint">一物一卡：N 件自动拆分为 N 张卡片（上限 500）</div>
        </el-form-item>
        <el-form-item label="原值合计" required>
          <el-input-number v-model="form.totalAmount" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="使用年限">
          <el-input-number v-model="form.lifeYears" :min="1" />
        </el-form-item>
        <el-form-item label="残值率">
          <el-input-number v-model="form.residualRate" :min="0" :max="0.99" :step="0.01" :precision="4" />
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="form.adjustReason" placeholder="年限偏离分类默认值时必填" />
        </el-form-item>
        <el-form-item label="使用部门">
          <el-input v-model="form.deptName" />
        </el-form-item>
        <el-form-item label="成本中心">
          <el-input v-model="form.costCenterCode" />
        </el-form-item>
        <el-form-item label="存放地点">
          <el-input v-model="form.storageLocation" />
        </el-form-item>
        <el-form-item v-if="mode === 'CIP'" label="部分转资">
          <el-checkbox v-model="partial">归集与结算有差额，选择部分转资</el-checkbox>
          <el-input v-if="partial" v-model="form.partialRemark" type="textarea" :rows="2"
            placeholder="未转资部分继续挂账在建工程，须填写说明（BR-4.13-08）" />
        </el-form-item>
      </el-form>
      <el-alert v-if="warnings.length" type="warning" :closable="false" style="margin-bottom:8px;"
        :title="warnings.join('；')" />
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getCapPageApi, createCapApi, getCapDetailApi, submitCapApi, getPendingListApi,
  getCategoryPageApi, getActiveCategoryApi, getDepreciationPreviewApi
} from '@/api/eam/asset'

const route = useRoute()
const userStore = useUserStore()
const mode = route.meta.mode || 'PURCHASE'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const keyword = ref('')
const status = ref('')
const filters = reactive({ current: 1, size: 10 })

const detail = reactive({ cap: null, category: null, preview: null })
const preview = ref(null)

const pendingVisible = ref(false)
const pendingLoading = ref(false)
const pendingRows = ref([])

const categories = ref([])
const formVisible = ref(false)
const partial = ref(false)
const warnings = ref([])
const form = reactive({
  sourceId: '', sourceDocNo: '', assetName: '', specModel: '', categoryCode: '',
  qty: 1, totalAmount: null, lifeYears: null, residualRate: null, adjustReason: '',
  deptName: '', costCenterCode: '', storageLocation: '', partialRemark: ''
})

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canEdit = computed(() => myRoles.value.some(r =>
  ['ROLE_ADMIN', 'ROLE_ASSET_ADMIN'].includes(r)))

function fmt(v) { return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }
function pct(v) { return `${(Number(v || 0) * 100).toFixed(2)}%` }

async function load() {
  loading.value = true
  try {
    const res = await getCapPageApi({ ...filters, mode, keyword: keyword.value, status: status.value })
    rows.value = (res.data && res.data.records) || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  const res = await getCapDetailApi(row.id)
  Object.assign(detail, res.data || {})
  preview.value = (res.data && res.data.preview) || null
}

async function doSubmit(row) {
  try {
    await submitCapApi(row.id)
    ElMessage.success('已提交审核（财务资产会计单签）')
    load()
  } catch (e) {
    // BR-07 原值超差等 422 由 request 层弹出
  }
}

async function openPending() {
  pendingVisible.value = true
  pendingLoading.value = true
  try {
    const res = await getPendingListApi()
    pendingRows.value = res.data || []
  } finally {
    pendingLoading.value = false
  }
}

async function startCap(row) {
  const catRes = await getCategoryPageApi({ current: 1, size: 100, status: 'ACTIVE' })
  categories.value = (catRes.data && catRes.data.records) || []
  Object.assign(form, {
    sourceId: row.sourceId, sourceDocNo: row.sourceDocNo, assetName: row.name || '',
    specModel: '', categoryCode: '', qty: 1, totalAmount: null, lifeYears: null,
    residualRate: null, adjustReason: '', deptName: '', costCenterCode: '',
    storageLocation: '', partialRemark: ''
  })
  partial.value = false
  warnings.value = []
  pendingVisible.value = false
  formVisible.value = true
}

async function onCategoryChange(code) {
  const res = await getActiveCategoryApi(code)
  const c = res.data
  if (c) {
    form.lifeYears = c.defaultLifeYears
    form.residualRate = Number(c.defaultResidualRate)
  }
}

async function doCreate() {
  saving.value = true
  warnings.value = []
  try {
    const res = await createCapApi({
      ...form, mode,
      partialFlag: partial.value ? '1' : '0'
    })
    warnings.value = (res.data && res.data.warnings) || []
    ElMessage.success('草稿已创建，请核对后提交审核')
    formVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.spacer { flex: 1; }
.hint { font-size: 12px; color: #909399; line-height: 1.6; }
</style>
