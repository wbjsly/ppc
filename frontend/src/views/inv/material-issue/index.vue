<template>
  <div class="mi-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="领料出库（4.5.2）：领料单 DRAFT → POSTED，FIFO 配批，可用量不足 422（spec material-issue）"
      description="自有领料扣减可用库存；寄售（VMI）领用执行物权转移——生成《寄售转自有凭证》并按领用时点协议价生成应付暂估。创建/过账限 ADMIN 与仓库。" />

    <div class="toolbar">
      <el-select v-model="filters.issueType" placeholder="类型" clearable style="width: 130px;"
        @change="load">
        <el-option label="自有领料" value="OWN" />
        <el-option label="寄售领用" value="VMI" />
      </el-select>
      <el-select v-model="filters.status" placeholder="状态" clearable style="width: 130px;" @change="load">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已过账" value="POSTED" />
        <el-option label="已作废" value="CANCELLED" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="单号/用途/部门" clearable style="width: 180px;"
        @clear="load" @keyup.enter="load" />
      <el-button @click="load">查询</el-button>
      <el-button type="primary" v-if="canWh" @click="openDialog">创建领料单</el-button>
    </div>

    <el-table :data="rows" size="small" border v-loading="loading" @expand-change="loadDetail">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div style="padding: 8px 16px;">
            <el-table :data="row._lines || []" size="mini" border>
              <el-table-column prop="itemCode" label="物料" width="150" />
              <el-table-column prop="itemName" label="名称" min-width="150" />
              <el-table-column prop="batchNo" label="FIFO 批次" width="150" />
              <el-table-column prop="qty" label="数量" width="100" />
              <el-table-column prop="unitPrice" label="协议价(VMI)" width="110" />
              <el-table-column prop="amount" label="金额" width="110" />
            </el-table>
            <div v-if="row.transferDocNo" style="margin-top: 6px; font-size: 12px; color: #67C23A;">
              《寄售转自有凭证》：{{ row.transferDocNo }}
            </div>
            <div v-if="row.status === 'CANCELLED'" style="margin-top: 6px; font-size: 12px; color: #F56C6C;">
              作废原因：{{ row.cancelReason }}
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="issueNo" label="领料单号" width="150" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.issueType === 'VMI' ? 'warning' : 'primary'">
            {{ row.issueType === 'VMI' ? '寄售领用' : '自有领料' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="workOrderNo" label="工单号" width="140" />
      <el-table-column prop="dept" label="部门" width="110" />
      <el-table-column prop="purpose" label="用途" min-width="140" />
      <el-table-column prop="lineCount" label="行数" width="60" />
      <el-table-column prop="transferDocNo" label="转自有凭证" width="150" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'POSTED' ? 'success' : (row.status === 'DRAFT' ? 'warning' : 'info')">
            {{ row.status === 'DRAFT' ? '草稿' : (row.status === 'POSTED' ? '已过账' : '已作废') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="postDate" label="过账时间" width="160" />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <template v-if="canWh && row.status === 'DRAFT'">
            <el-button link type="primary" size="small" @click="post(row)">过账</el-button>
            <el-button link type="danger" size="small" @click="cancel(row)">作废</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top: 10px; justify-content: flex-end;"
      layout="total, prev, pager, next" :total="total" :page-size="10"
      :current-page="filters.current" @current-change="p => { filters.current = p; load() }" />

    <!-- 创建对话框 -->
    <el-dialog v-model="dialog" title="创建领料单（FIFO 配批）" width="760px" :close-on-click-modal="false">
      <el-form :model="form" label-width="90px" size="small">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="领料类型">
              <el-select v-model="form.issueType" style="width: 100%;">
                <el-option label="自有领料" value="OWN" />
                <el-option label="寄售领用" value="VMI" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="供应商" v-if="form.issueType === 'VMI'" required>
              <el-select v-model="form.supplierId" filterable placeholder="寄售供应商" style="width: 100%;">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="工单号" required>
              <el-input v-model="form.workOrderNo" placeholder="BR-4.2-37" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="领料部门">
              <el-input v-model="form.dept" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="用途">
              <el-input v-model="form.purpose" />
            </el-form-item>
          </el-col>
          <el-col :span="14">
            <el-form-item label="物料编码" required>
              <el-input v-model="form.itemCode" placeholder="如 RM0001000001" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6">
            <el-form-item label="数量" required>
              <el-input-number v-model="form.qty" :min="0.0001" :precision="3" :controls="false"
                style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label-width="0">
              <el-button @click="doPreview"
                :disabled="!form.itemCode || !form.qty || (form.issueType === 'VMI' && !form.supplierId)">配批预检</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <el-table v-if="preview.length" :data="preview" size="mini" border style="margin-bottom: 8px;" max-height="200">
        <el-table-column prop="itemCode" label="物料" width="150" />
        <el-table-column prop="batchNo" label="FIFO 批次" width="150" />
        <el-table-column prop="qty" label="本批数量" width="100" />
      </el-table>
      <el-alert v-if="previewErr" type="error" :closable="false" :title="previewErr" style="margin-bottom: 8px;" />
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!preview.length" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getIssuePageApi, getIssueDetailApi, previewIssueApi, createIssueApi,
  postIssueApi, cancelIssueApi
} from '@/api/inv/issue'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const roles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canWh = computed(() => roles.value.includes('ROLE_ADMIN') || roles.value.includes('ROLE_WAREHOUSE'))

const loading = ref(false)
const submitting = ref(false)
const rows = ref([])
const total = ref(0)
const suppliers = ref([])
const filters = reactive({ current: 1, issueType: '', status: '', keyword: '' })

const dialog = ref(false)
const form = reactive({ issueType: 'OWN', supplierId: '', workOrderNo: '', dept: '', purpose: '', itemCode: '', qty: 100 })
const preview = ref([])
const previewErr = ref('')

async function load() {
  loading.value = true
  try {
    const res = await getIssuePageApi({
      current: filters.current, size: 10,
      issueType: filters.issueType || undefined,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined
    })
    rows.value = res.data.records || []
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

async function loadDetail(row) {
  if (row._lines) return
  const res = await getIssueDetailApi(row.id)
  row._lines = res.data.lines || []
}

function openDialog() {
  preview.value = []
  previewErr.value = ''
  dialog.value = true
}

async function doPreview() {
  previewErr.value = ''
  preview.value = []
  try {
    const res = await previewIssueApi({
      issueType: form.issueType,
      supplierId: form.issueType === 'VMI' ? form.supplierId : undefined,
      workOrderNo: form.workOrderNo || 'PREVIEW',
      lines: [{ itemCode: form.itemCode, qty: form.qty }]
    })
    preview.value = res.data.lines || []
  } catch (e) {
    previewErr.value = (e && e.message) || '配批预检失败'
  }
}

async function create() {
  submitting.value = true
  try {
    const res = await createIssueApi({
      issueType: form.issueType,
      supplierId: form.issueType === 'VMI' ? form.supplierId : undefined,
      workOrderNo: form.workOrderNo, dept: form.dept, purpose: form.purpose,
      lines: [{ itemCode: form.itemCode, qty: form.qty }]
    })
    ElMessage.success(`领料单 ${res.data.issue.issueNo} 已创建（DRAFT）`)
    dialog.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function post(row) {
  const tip = row.issueType === 'VMI'
    ? '确认过账？将执行物权转移：扣寄售库存、加自有库存、生成《寄售转自有凭证》与应付暂估。'
    : '确认过账？将按 FIFO 扣减自有可用库存。'
  await ElMessageBox.confirm(`${tip}（单号 ${row.issueNo}）`, '过账确认', { type: 'warning' })
  const res = await postIssueApi(row.id)
  ElMessage.success(row.issueType === 'VMI'
    ? `过账成功，转自有凭证：${(res.data.issue && res.data.issue.transferDocNo) || ''}`
    : '过账成功')
  load()
}

async function cancel(row) {
  const { value } = await ElMessageBox.prompt('作废原因（不少于 2 字）', '作废领料单', { type: 'warning' })
  await cancelIssueApi(row.id, value)
  ElMessage.success('已作废（库存无变动）')
  load()
}

onMounted(async () => {
  const res = await getSupplierPageApi({ current: 1, size: 200 })
  suppliers.value = (res.data && res.data.records) || res.data || []
  await load()
})
</script>

<style scoped>
.mi-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
</style>
