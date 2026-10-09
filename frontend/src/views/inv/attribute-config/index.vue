<template>
  <div class="attr-config-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="属性配置（4.1.3）"
      description="仓库域五类字典：仓库类型 / 仓位类型 / 温湿度 / 危化品 / 洁净等级。编码创建后不可改；停用条目退出新数据可选范围，既有引用不受影响。"
    />
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">属性配置</span>
          <el-button type="primary" :icon="Plus" @click="openForm()">新增条目</el-button>
        </div>
      </template>

      <el-tabs v-model="activeType" @tab-change="loadItems">
        <el-tab-pane v-for="t in INV_DICT_TYPES" :key="t.type" :label="t.label" :name="t.type" />
      </el-tabs>

      <el-table :data="items" v-loading="loading" stripe size="small">
        <el-table-column prop="dictCode" label="编码" width="180" />
        <el-table-column prop="dictName" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="sortOrder" label="排序" width="90" align="center" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">变更</el-button>
            <el-button v-if="row.status === '1'" link type="warning" @click="handleDisable(row)">停用</el-button>
            <el-button v-else link type="success" @click="handleEnable(row)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="formVisible" :title="form.id ? '变更条目' : '新增条目'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="字典类型">
          <el-input :model-value="typeLabel(form.dictType)" disabled />
        </el-form-item>
        <el-form-item label="编码" prop="dictCode">
          <el-input v-model="form.dictCode" :disabled="!!form.id" maxlength="32"
                    :placeholder="form.id ? '创建后不可修改' : '如 AMBIENT'" />
        </el-form-item>
        <el-form-item label="名称" prop="dictName">
          <el-input v-model="form.dictName" maxlength="64" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
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
  INV_DICT_TYPES, getDictItemsApi,
  createDictItemApi, updateDictItemApi, enableDictItemApi, disableDictItemApi
} from '@/api/inv/dict'

const activeType = ref('WAREHOUSE_TYPE')
const items = ref([])
const loading = ref(false)
const saving = ref(false)

const formVisible = ref(false)
const formRef = ref(null)
const form = reactive({})
const rules = {
  dictCode: [{ required: true, message: '请输入编码', trigger: 'blur' }],
  dictName: [{ required: true, message: '请输入名称', trigger: 'blur' }]
}

function typeLabel(type) {
  const t = INV_DICT_TYPES.find(x => x.type === type)
  return t ? `${t.label}（${t.type}）` : type
}

async function loadItems() {
  loading.value = true
  try {
    const res = await getDictItemsApi({ dictType: activeType.value })
    items.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openForm(row) {
  Object.assign(form, row ? { ...row } : {
    dictType: activeType.value, dictCode: '', dictName: '', sortOrder: 0
  })
  formVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateDictItemApi(form)
      ElMessage.success('变更已保存（编码不可改）')
    } else {
      await createDictItemApi(form)
      ElMessage.success('新增成功')
    }
    formVisible.value = false
    loadItems()
  } finally {
    saving.value = false
  }
}

async function handleDisable(row) {
  await ElMessageBox.confirm(
    `确认停用「${row.dictName}」？停用后退出新数据可选范围，既有引用不受影响。`,
    '停用确认', { type: 'warning' }
  )
  await disableDictItemApi(row.id)
  ElMessage.success('已停用')
  loadItems()
}

async function handleEnable(row) {
  await enableDictItemApi(row.id)
  ElMessage.success('已启用')
  loadItems()
}

onMounted(loadItems)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
