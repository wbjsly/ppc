<template>
  <div class="change-window-page">
    <el-card shadow="never">
      <div slot="header" class="card-header">
        <span>变更窗口维护（16.4.1）</span>
        <div class="header-actions">
          <el-tag :type="inWindowNow ? 'success' : 'info'" size="large">
            {{ inWindowNow ? '当前处于变更窗口内' : '当前在变更窗口外（窗口外提交将升级 CCB）' }}
          </el-tag>
          <el-button type="primary" @click="openDialog(null)">新建窗口</el-button>
        </div>
      </div>

      <el-table :data="windows" border stripe v-loading="loading">
        <el-table-column prop="name" label="窗口名称" min-width="160" />
        <el-table-column prop="dayOfWeek" label="星期" width="100" :formatter="fmtDay" />
        <el-table-column prop="startTime" label="开始时刻（含）" width="130" />
        <el-table-column prop="endTime" label="结束时刻（不含）" width="140" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'danger'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link :type="row.status === '1' ? 'warning' : 'success'"
                       @click="toggleStatus(row)">{{ row.status === '1' ? '停用' : '启用' }}</el-button>
            <el-button link type="danger" @click="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog :title="form.id ? '编辑窗口' : '新建窗口'" v-model="dialogVisible" width="480px"
               :close-on-click-modal="false">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="130px">
        <el-form-item label="窗口名称" prop="name">
          <el-input v-model="form.name" placeholder="如：周三夜间窗口" maxlength="100" />
        </el-form-item>
        <el-form-item label="星期" prop="dayOfWeek">
          <el-select v-model="form.dayOfWeek" placeholder="选择星期">
            <el-option v-for="d in 7" :key="d" :label="'周' + cnDay(d)" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时刻（含）" prop="startTime">
          <el-time-picker v-model="form.startTime" format="HH:mm" value-format="HH:mm"
                          placeholder="选择开始时刻" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束时刻（不含）" prop="endTime">
          <el-time-picker v-model="form.endTime" format="HH:mm" value-format="HH:mm"
                          placeholder="选择结束时刻" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio label="1">启用</el-radio>
            <el-radio label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { windowList, saveWindow, deleteWindow } from '@/api/system/permission-request'

const DAY_CN = ['一', '二', '三', '四', '五', '六', '日']

const windows = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const formRef = ref(null)
const inWindowNow = ref(false)

const form = ref({ id: null, name: '', dayOfWeek: null, startTime: '', endTime: '', status: '1' })
const rules = {
  name: [{ required: true, message: '窗口名称必填', trigger: 'blur' }],
  dayOfWeek: [{ required: true, message: '星期必选', trigger: 'change' }],
  startTime: [{ required: true, message: '开始时刻必填', trigger: 'change' }],
  endTime: [{ required: true, message: '结束时刻必填', trigger: 'change' }]
}

const cnDay = (d) => DAY_CN[d - 1]
const fmtDay = (row) => '周' + cnDay(row.dayOfWeek)

/** 当前是否在窗口内（前端近似判定：按启用窗口周期比对本地时间） */
function refreshInWindow() {
  const now = new Date()
  const day = now.getDay() === 0 ? 7 : now.getDay()
  const t = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds()
  inWindowNow.value = windows.value.some((w) => {
    if (w.status !== '1' || w.dayOfWeek !== day) return false
    const s = toSec(w.startTime)
    const e = toSec(w.endTime)
    return t >= s && t < e
  })
}

function toSec(hhmmss) {
  const parts = String(hhmmss).split(':').map(Number)
  return (parts[0] || 0) * 3600 + (parts[1] || 0) * 60 + (parts[2] || 0)
}

async function load() {
  loading.value = true
  try {
    const res = await windowList()
    windows.value = res.data || []
    refreshInWindow()
  } finally {
    loading.value = false
  }
}

function openDialog(row) {
  form.value = row
    ? { id: row.id, name: row.name, dayOfWeek: row.dayOfWeek,
        startTime: String(row.startTime).slice(0, 5), endTime: String(row.endTime).slice(0, 5),
        status: row.status }
    : { id: null, name: '', dayOfWeek: null, startTime: '', endTime: '', status: '1' }
  dialogVisible.value = true
  formRef.value && formRef.value.clearValidate()
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    await saveWindow(form.value)
    ElMessage.success('窗口已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  await saveWindow({ ...row, status: row.status === '1' ? '0' : '1' })
  ElMessage.success(row.status === '1' ? '已停用' : '已启用')
  await load()
}

async function removeRow(row) {
  await ElMessageBox.confirm(`确认删除窗口「${row.name}」？`, '删除确认', { type: 'warning' })
  await deleteWindow(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.change-window-page { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 12px; align-items: center; }
</style>
