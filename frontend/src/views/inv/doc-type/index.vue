<template>
  <div class="page">
    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item>
        <el-button v-if="isAdmin" type="primary" @click="openCreate">新增类型</el-button>
      </el-form-item>
      <span class="tip">配置只承载注册语义（方向/分配默认/校验开关）——业务硬规则在服务端，改配置不可绕过校验（偏差 D2）</span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="typeCode" label="类型码" width="180" />
      <el-table-column label="方向" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.direction === 'IN' ? 'success' : 'warning'" size="small">
            {{ row.direction === 'IN' ? '入库' : '出库' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="typeName" label="类型名称" width="150" />
      <el-table-column prop="flowPrefix" label="流水前缀" width="90" />
      <el-table-column label="缺省分配" width="120">
        <template #default="{ row }">{{ row.defaultAlloc === 'AUTO_FIFO' ? '引擎FIFO分配' : '指定批次' }}</template>
      </el-table-column>
      <el-table-column label="批次必填" width="90" align="center">
        <template #default="{ row }">{{ row.needBatch === 1 ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="序列判重" width="90" align="center">
        <template #default="{ row }">{{ row.needSerial === 1 ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
            {{ row.enabled === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column v-if="isAdmin" label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">修改</el-button>
          <el-button v-if="row.enabled === 1" link type="warning" size="small"
            @click="toggle(row, false)">停用</el-button>
          <el-button v-else link type="success" size="small" @click="toggle(row, true)">启用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/修改对话框 -->
    <el-dialog v-model="formVisible" :title="editing ? '修改类型' : '新增类型'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="类型码" prop="typeCode">
          <el-input v-model="form.typeCode" :disabled="editing"
            placeholder="如 WIP_IN（大写字母/数字/下划线，创建后不可改）" />
        </el-form-item>
        <el-form-item label="类型名称" prop="typeName">
          <el-input v-model="form.typeName" maxlength="64" />
        </el-form-item>
        <el-form-item label="方向" prop="direction">
          <el-radio-group v-model="form.direction" :disabled="editing">
            <el-radio label="IN">入库</el-radio>
            <el-radio label="OUT">出库</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="缺省分配" prop="defaultAlloc">
          <el-select v-model="form.defaultAlloc" style="width: 100%">
            <el-option label="引擎 FIFO+效期分配" value="AUTO_FIFO" />
            <el-option label="请求指定批次" value="MANUAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次必填">
          <el-switch v-model="form.needBatch" :active-value="1" :inactive-value="0" />
          <span class="form-tip">开=过账校验批次并联动建档</span>
        </el-form-item>
        <el-form-item label="序列判重">
          <el-switch v-model="form.needSerial" :active-value="1" :inactive-value="0" />
          <span class="form-tip">开=过账强制序列判重（物料 serialFlag 优先级更高）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDocTypesApi, createDocTypeApi, updateDocTypeApi, enableDocTypeApi, disableDocTypeApi
} from '@/api/inv/doc-type'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const formVisible = ref(false)
const editing = ref(false)
const formRef = ref(null)
const form = ref({})

const rules = {
  typeCode: [{ required: true, message: '请输入类型码', trigger: 'blur' }],
  typeName: [{ required: true, message: '请输入类型名称', trigger: 'blur' }],
  direction: [{ required: true, message: '请选择方向', trigger: 'change' }],
  defaultAlloc: [{ required: true, message: '请选择缺省分配', trigger: 'change' }]
}

async function loadData() {
  loading.value = true
  try {
    const res = await getDocTypesApi()
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  form.value = { typeCode: '', typeName: '', direction: 'IN', defaultAlloc: 'MANUAL',
    needBatch: 1, needSerial: 0, remark: '' }
  formVisible.value = true
  requestAnimationFrame(() => formRef.value?.clearValidate())
}

function openEdit(row) {
  editing.value = true
  form.value = { ...row }
  formVisible.value = true
  requestAnimationFrame(() => formRef.value?.clearValidate())
}

async function submit() {
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    const res = editing.value
      ? await updateDocTypeApi(form.value)
      : await createDocTypeApi(form.value)
    if (res.code === 200) {
      ElMessage.success(editing.value ? '已保存' : '已新增')
      formVisible.value = false
      loadData()
    } else {
      ElMessage.error(res.message || '保存失败')
    }
  } finally {
    saving.value = false
  }
}

async function toggle(row, enable) {
  await ElMessageBox.confirm(
    `${enable ? '启用' : '停用'}类型 ${row.typeCode}？` +
    (enable ? '' : '停用后该类型将拒绝新发起的过账（历史流水保留）。'),
    enable ? '启用确认' : '停用确认', { type: 'warning' })
  const res = enable ? await enableDocTypeApi(row.id) : await disableDocTypeApi(row.id)
  if (res.code === 200) {
    ElMessage.success(enable ? '已启用' : '已停用')
    loadData()
  } else {
    ElMessage.error(res.message || '操作失败')
  }
}

onMounted(loadData)
</script>

<style scoped>
.page { padding: 4px; }
.filter-bar { margin-bottom: 4px; }
.tip { color: #909399; font-size: 12px; line-height: 32px; }
.form-tip { color: #909399; font-size: 12px; margin-left: 10px; }
</style>
