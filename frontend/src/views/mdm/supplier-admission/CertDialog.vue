<template>
  <el-dialog :model-value="visible" @update:model-value="v => emit('update:visible', v)"
             :title="`资质证照：${supplier?.supplierName || ''}`" width="780px" @open="load">
    <el-alert v-if="supplier?.status === 'CERT_EXPIRED'" type="error" :closable="false" style="margin-bottom: 10px;"
      title="该供应商处于「证照过期」受限态：请更新证照（有效期 > 今天）后执行核验解除。" />

    <el-table :data="certs" v-loading="loading" size="small" border>
      <el-table-column label="类型" width="130">
        <template #default="{ row }">{{ typeName(row.certType) }}</template>
      </el-table-column>
      <el-table-column prop="certNo" label="证照编号" width="140">
        <template #default="{ row }">{{ row.certNo || '—' }}</template>
      </el-table-column>
      <el-table-column prop="issueDate" label="发证日" width="110">
        <template #default="{ row }">{{ row.issueDate || '—' }}</template>
      </el-table-column>
      <el-table-column label="有效期至" width="150">
        <template #default="{ row }">
          {{ row.expireDate }}
          <el-tag v-if="isExpired(row)" type="danger" size="small">已过期</el-tag>
          <el-tag v-else-if="isNear(row)" type="warning" size="small">临期</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-divider content-position="left">{{ editingId ? '编辑证照' : '新增证照' }}</el-divider>
    <el-form inline>
      <el-form-item label="类型">
        <el-select v-model="certForm.certType" style="width: 140px;">
          <el-option label="营业执照" value="LICENSE" />
          <el-option label="行业认证" value="INDUSTRY" />
          <el-option label="其它" value="OTHER" />
        </el-select>
      </el-form-item>
      <el-form-item label="编号">
        <el-input v-model="certForm.certNo" maxlength="64" style="width: 160px;" />
      </el-form-item>
      <el-form-item label="发证日">
        <el-date-picker v-model="certForm.issueDate" type="date" value-format="YYYY-MM-DD" style="width: 140px;" />
      </el-form-item>
      <el-form-item label="有效期至" required>
        <el-date-picker v-model="certForm.expireDate" type="date" value-format="YYYY-MM-DD" style="width: 140px;" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save">{{ editingId ? '更新' : '添加' }}</el-button>
        <el-button v-if="editingId" @click="resetEdit">取消编辑</el-button>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:visible', false)">关闭</el-button>
      <el-button v-if="supplier?.status === 'CERT_EXPIRED'" type="success" :loading="saving" @click="verify">
        核验解除受限
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getCertsApi, saveCertApi, updateCertApi, deleteCertApi, verifyCertsApi
} from '@/api/mdm/supplier-admission'

const props = defineProps({
  visible: { type: Boolean, default: false },
  supplier: { type: Object, default: null }
})
const emit = defineEmits(['update:visible', 'saved'])

const loading = ref(false)
const saving = ref(false)
const certs = ref([])
const editingId = ref(null)
const certForm = ref({})

const ALERT_DAYS = 30

function typeName(t) {
  return { LICENSE: '营业执照', INDUSTRY: '行业认证', OTHER: '其它' }[t] || t
}
function isExpired(row) {
  return row.expireDate && row.expireDate < new Date().toISOString().slice(0, 10)
}
function isNear(row) {
  const d = new Date(row.expireDate)
  const now = new Date()
  return !isExpired(row) && (d - now) / 86400000 <= ALERT_DAYS
}

async function load() {
  loading.value = true
  try {
    const res = await getCertsApi(props.supplier.id)
    certs.value = res.data
    resetEdit()
  } finally {
    loading.value = false
  }
}

function resetEdit() {
  editingId.value = null
  certForm.value = { certType: 'LICENSE', certNo: '', issueDate: '', expireDate: '' }
}

function edit(row) {
  editingId.value = row.id
  certForm.value = { ...row }
}

async function save() {
  if (!certForm.value.expireDate) {
    ElMessage.warning('有效期必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateCertApi(editingId.value, certForm.value)
      ElMessage.success('证照已更新')
    } else {
      await saveCertApi(props.supplier.id, certForm.value)
      ElMessage.success('证照已添加')
    }
    await load()
    emit('saved')
  } catch (e) {
    // 422 已单点提示
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`删除证照「${typeName(row.certType)}」？`, '删除确认', { type: 'warning' })
  } catch (e) {
    return
  }
  await deleteCertApi(row.id)
  ElMessage.success('已删除')
  await load()
  emit('saved')
}

async function verify() {
  try {
    await ElMessageBox.confirm('确认证照已更新且核验通过，解除受限恢复合格？', '核验解除', { type: 'warning' })
  } catch (e) {
    return
  }
  saving.value = true
  try {
    await verifyCertsApi(props.supplier.id, '证照更新核验通过，解除受限')
    ElMessage.success('已解除受限，恢复合格')
    emit('update:visible', false)
    emit('saved')
  } catch (e) {
    // 422 无有效证照已提示
  } finally {
    saving.value = false
  }
}
</script>
