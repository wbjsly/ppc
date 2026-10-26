<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">职级管理（10.2.4）</span>
        <div>
          <el-button type="primary" @click="openCreate">新建职级</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="grades">
        <el-table-column prop="gradeNo" label="编码" width="100" />
        <el-table-column prop="name" label="职级名称" min-width="160" />
        <el-table-column label="序列" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ row.series === 'P' ? 'P 管理' : 'M 技术' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="带宽（月薪）" width="180">
          <template #default="{ row }">
            <span v-if="row.salMin != null">{{ row.salMin }} ~ {{ row.salMax }}</span>
            <span v-else class="muted">未设置</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === '1'" link type="danger" size="small"
                       @click="toggle(row, '0')">停用</el-button>
            <el-button v-else link type="success" size="small" @click="toggle(row, '1')">启用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-alert type="info" :closable="false" style="margin-top: 12px"
                title="带宽变更走 GRADE_BAND 调整单审批留痕（C-4.7-06 同口径）；带宽挂职级，岗位经关联间接获得" />
    </el-card>

    <!-- 新建/编辑 -->
    <el-dialog v-model="editVisible" :title="editing.id ? '编辑职级' : '新建职级'" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="form.name" :disabled="!!editing.id" />
        </el-form-item>
        <el-form-item label="序列">
          <el-radio-group v-model="form.series" :disabled="!!editing.id">
            <el-radio value="P">P 管理</el-radio>
            <el-radio value="M">M 技术</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="带宽下限">
          <el-input-number v-model="form.salMin" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="带宽上限">
          <el-input-number v-model="form.salMax" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">{{ editing.id ? '提交' : '确定' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { gradeList, createGrade, updateGrade, toggleGrade } from '@/api/hr/org-management'

const grades = ref([])
const editVisible = ref(false)
const editing = ref({})
const form = ref({ name: '', series: 'M', salMin: null, salMax: null })

async function load() {
  const { data } = await gradeList()
  grades.value = data || []
}

function openCreate() {
  editing.value = {}
  form.value = { name: '', series: 'M', salMin: null, salMax: null }
  editVisible.value = true
}
function openEdit(row) {
  editing.value = row
  form.value = {
    name: row.name,
    series: row.series,
    salMin: row.salMin == null ? null : Number(row.salMin),
    salMax: row.salMax == null ? null : Number(row.salMax)
  }
  editVisible.value = true
}
async function submit() {
  try {
    if (editing.value.id) {
      await updateGrade(editing.value.id, form.value)
      ElMessage.success('已提交（带宽变更走调整单审批）')
    } else {
      await createGrade(form.value)
      ElMessage.success('已创建')
    }
    editVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message || '提交失败')
  }
}
async function toggle(row, status) {
  try {
    await toggleGrade(row.id, status)
    ElMessage.success(status === '0' ? '已停用' : '已启用')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message || '操作失败')
  }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
.muted { color: #999; }
</style>
