<template>
  <div class="batch-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="批号管理（4.2.1）"
      description="批次号系统生成（B+年月日+-+4位流水）或手工录入，同物料唯一且创建后不可改；批次管理物料有效期至必填。有效期至为 FIFO/效期预警的数据源。"
    />
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">批号管理</span>
          <div class="header-actions">
            <el-input v-model="query.itemCode" placeholder="物料编码" clearable style="width: 150px;" @keyup.enter="loadData" />
            <el-input v-model="query.keyword" placeholder="批次号/供应商批次" clearable style="width: 170px;" @keyup.enter="loadData" />
            <el-date-picker v-model="expiryRange" type="daterange" value-format="YYYY-MM-DD"
                            start-placeholder="效期起" end-placeholder="效期止" style="width: 240px;" />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 100px;">
              <el-option label="正常" value="1" />
              <el-option label="关闭" value="0" />
            </el-select>
            <el-button type="primary" @click="loadData">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openForm()">新建批次</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="batchNo" label="批次号" width="150" />
        <el-table-column prop="itemCode" label="物料编码" width="130" />
        <el-table-column prop="itemName" label="物料名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="productionDate" label="生产日期" width="110">
          <template #default="{ row }">{{ row.productionDate || '-' }}</template>
        </el-table-column>
        <el-table-column prop="expiryDate" label="有效期至" width="110">
          <template #default="{ row }">
            <span :class="expiryClass(row.expiryDate)">{{ row.expiryDate || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="supplierBatchNo" label="供应商批次" width="130">
          <template #default="{ row }">{{ row.supplierBatchNo || '-' }}</template>
        </el-table-column>
        <el-table-column prop="sourceDocNo" label="来源单据" width="130">
          <template #default="{ row }">{{ row.sourceDocNo || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
              {{ row.status === '1' ? '正常' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">变更</el-button>
            <el-button v-if="row.status === '1'" link type="warning" @click="handleDisable(row)">关闭</el-button>
            <el-button v-else link type="success" @click="handleEnable(row)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 变更 -->
    <el-dialog v-model="formVisible" :title="form.id ? '变更批次' : '新建批次'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="批次号">
          <el-input v-model="form.batchNo" :disabled="!!form.id"
                    :placeholder="form.id ? '' : '留空 = 系统自动生成（B+年月日+-+4位流水）'" />
        </el-form-item>
        <el-form-item label="物料编码" prop="itemCode">
          <el-input v-model="form.itemCode" :disabled="!!form.id" maxlength="64"
                    :placeholder="form.id ? '' : '如 IT-0001（批次管理物料效期必填）'" />
        </el-form-item>
        <el-form-item label="生产日期">
          <el-date-picker v-model="form.productionDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="form.expiryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="供应商批次">
          <el-input v-model="form.supplierBatchNo" maxlength="64" />
        </el-form-item>
        <el-form-item label="来源单据">
          <el-input v-model="form.sourceDocNo" maxlength="64" placeholder="如 PO 号 / 生产工单号" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  getBatchesApi, createBatchApi, updateBatchApi,
  enableBatchApi, disableBatchApi
} from '@/api/inv/batch'

const rows = ref([])
const loading = ref(false)
const saving = ref(false)
const query = reactive({ itemCode: '', keyword: '', status: '' })
const expiryRange = ref(null)

const formVisible = ref(false)
const formRef = ref(null)
const form = reactive({})
const rules = {
  itemCode: [{ required: true, message: '请输入物料编码', trigger: 'blur' }]
}

function expiryClass(date) {
  if (!date) return ''
  const days = Math.ceil((new Date(date) - Date.now()) / 86400000)
  if (days <= 30) return 'exp-red'
  if (days <= 60) return 'exp-orange'
  if (days <= 90) return 'exp-yellow'
  return ''
}

async function loadData() {
  loading.value = true
  try {
    const res = await getBatchesApi({
      itemCode: query.itemCode || undefined,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      expiryFrom: expiryRange.value ? expiryRange.value[0] : undefined,
      expiryTo: expiryRange.value ? expiryRange.value[1] : undefined
    })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openForm(row) {
  Object.assign(form, row ? { ...row } : {
    batchNo: '', itemCode: '', productionDate: '', expiryDate: '',
    supplierBatchNo: '', sourceDocNo: '', remark: ''
  })
  formVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateBatchApi(form)
      ElMessage.success('变更已保存（批次号不可改）')
    } else {
      await createBatchApi(form)
      ElMessage.success('新建成功')
    }
    formVisible.value = false
    loadData()
  } finally {
    saving.value = false
  }
}

async function handleDisable(row) {
  await ElMessageBox.confirm(
    `确认关闭批次「${row.batchNo}」？关闭后退出新数据可选范围，既有引用不受影响。`,
    '关闭确认', { type: 'warning' }
  )
  await disableBatchApi(row.id)
  ElMessage.success('已关闭')
  loadData()
}

async function handleEnable(row) {
  await enableBatchApi(row.id)
  ElMessage.success('已启用')
  loadData()
}

onMounted(loadData)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.exp-yellow { color: #e6a23c; font-weight: bold; }
.exp-orange { color: #ff7d00; font-weight: bold; }
.exp-red { color: #f56c6c; font-weight: bold; }
</style>
