<template>
  <div class="serial-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="序列管理（4.2.2）"
      description="序列号手工录入、全局唯一（含历史，重复即串码）；状态机：在库 → 已出库/冻结/报废，冻结可解冻，已出库与报废为终态不可回退。流转全程留痕。"
    />
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">序列管理</span>
          <div class="header-actions">
            <el-input v-model="query.serialNo" placeholder="序列号" clearable style="width: 160px;" @keyup.enter="loadData" />
            <el-input v-model="query.itemCode" placeholder="物料编码" clearable style="width: 140px;" @keyup.enter="loadData" />
            <el-input v-model="query.batchNo" placeholder="批次号" clearable style="width: 140px;" @keyup.enter="loadData" />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px;" @change="loadData">
              <el-option label="在库" value="IN_STOCK" />
              <el-option label="已出库" value="OUT" />
              <el-option label="冻结" value="FROZEN" />
              <el-option label="报废" value="SCRAPPED" />
            </el-select>
            <el-button type="primary" @click="loadData">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openForm()">新建序列</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="serialNo" label="序列号" width="160" />
        <el-table-column prop="itemCode" label="物料编码" width="130" />
        <el-table-column prop="itemName" label="物料名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="batchNo" label="批次" width="130">
          <template #default="{ row }">{{ row.batchNo || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="locationRemark" label="位置" width="120">
          <template #default="{ row }">{{ row.locationRemark || '-' }}</template>
        </el-table-column>
        <el-table-column prop="sourceDocNo" label="来源单据" width="120">
          <template #default="{ row }">{{ row.sourceDocNo || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLogs(row)">流转</el-button>
            <el-button v-if="row.status === 'IN_STOCK'" link type="warning"
                       @click="doTransition(row, 'FROZEN')">冻结</el-button>
            <el-button v-if="row.status === 'FROZEN'" link type="success"
                       @click="doTransition(row, 'IN_STOCK')">解冻</el-button>
            <el-button v-if="row.status === 'IN_STOCK'" link type="primary"
                       @click="doTransition(row, 'OUT')">出库</el-button>
            <el-button v-if="row.status === 'IN_STOCK'" link type="danger"
                       @click="doTransition(row, 'SCRAPPED')">报废</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建序列 -->
    <el-dialog v-model="formVisible" title="新建序列" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="序列号" prop="serialNo">
          <el-input v-model="form.serialNo" maxlength="64" placeholder="实物贴码，全局唯一（重复=串码）" />
        </el-form-item>
        <el-form-item label="物料编码" prop="itemCode">
          <el-input v-model="form.itemCode" maxlength="64" placeholder="如 IT-0001" />
        </el-form-item>
        <el-form-item label="批次号">
          <el-input v-model="form.batchNo" maxlength="64" placeholder="可空" />
        </el-form-item>
        <el-form-item label="位置备注">
          <el-input v-model="form.locationRemark" maxlength="255" />
        </el-form-item>
        <el-form-item label="来源单据">
          <el-input v-model="form.sourceDocNo" maxlength="64" />
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

    <!-- 流转记录 -->
    <el-dialog v-model="logsVisible" :title="`流转记录 - ${current.serialNo || ''}`" width="640px">
      <el-table :data="logs" stripe size="small" max-height="400">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column label="流转" min-width="180">
          <template #default="{ row }">
            {{ statusName(row.fromStatus) }} → {{ statusName(row.toStatus) }}
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="150">
          <template #default="{ row }">{{ row.reason || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createBy" label="操作人" width="130" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  getSerialsApi, createSerialApi, transitionSerialApi, getSerialLogsApi
} from '@/api/inv/serial'

const rows = ref([])
const logs = ref([])
const loading = ref(false)
const saving = ref(false)
const query = reactive({ serialNo: '', itemCode: '', batchNo: '', status: '' })

const formVisible = ref(false)
const formRef = ref(null)
const form = reactive({})
const rules = {
  serialNo: [{ required: true, message: '请输入序列号', trigger: 'blur' }],
  itemCode: [{ required: true, message: '请输入物料编码', trigger: 'blur' }]
}

const logsVisible = ref(false)
const current = reactive({})

function statusName(s) {
  return { IN_STOCK: '在库', OUT: '已出库', FROZEN: '冻结', SCRAPPED: '报废' }[s] || s || '-'
}
function statusTag(s) {
  return { IN_STOCK: 'success', OUT: 'info', FROZEN: 'warning', SCRAPPED: 'danger' }[s] || 'info'
}

async function loadData() {
  loading.value = true
  try {
    const res = await getSerialsApi({
      serialNo: query.serialNo || undefined,
      itemCode: query.itemCode || undefined,
      batchNo: query.batchNo || undefined,
      status: query.status || undefined
    })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openForm() {
  Object.assign(form, { serialNo: '', itemCode: '', batchNo: '', locationRemark: '', sourceDocNo: '', remark: '' })
  formVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  saving.value = true
  try {
    await createSerialApi(form)
    ElMessage.success('新建成功（状态：在库）')
    formVisible.value = false
    loadData()
  } finally {
    saving.value = false
  }
}

const TRANSITION_LABEL = { FROZEN: '冻结', IN_STOCK: '解冻', OUT: '出库', SCRAPPED: '报废' }

async function doTransition(row, toStatus) {
  const label = TRANSITION_LABEL[toStatus]
  const needReason = row.status === 'FROZEN' && toStatus === 'IN_STOCK' // 解冻原因必填（服务端强校验）
  let reason = null
  if (needReason) {
    try {
      const { value } = await ElMessageBox.prompt(
        `对「${row.serialNo}」解冻，必须填写原因：`,
        '解冻确认',
        {
          type: 'warning',
          inputPlaceholder: '解冻原因（必填）',
          inputValidator: (v) => (v && v.trim().length > 0) || '解冻必须填写原因'
        }
      )
      reason = value.trim()
    } catch (e) {
      return // 取消
    }
  } else {
    try {
      await ElMessageBox.confirm(`确认对「${row.serialNo}」执行${label}？原因选填，将随流转留痕。`, `${label}确认`, {
        type: 'warning'
      })
    } catch (e) {
      return
    }
  }
  await transitionSerialApi(row.id, { toStatus, reason })
  ElMessage.success(`${label}成功`)
  loadData()
}

async function openLogs(row) {
  Object.assign(current, row)
  const res = await getSerialLogsApi(row.id)
  logs.value = res.data || []
  logsVisible.value = true
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
</style>
