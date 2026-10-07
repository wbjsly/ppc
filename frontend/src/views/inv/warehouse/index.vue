<template>
  <div class="warehouse-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="仓库档案（4.1.1）"
      description="编码系统生成且创建后不可改；停用仓不被新发货单与新预留选用（在途单据不受影响）。存量库存已按仓库维度回填主仓 WH-MAIN。"
    />
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">仓库档案</span>
          <div class="header-actions">
            <el-input
              v-model="query.keyword" placeholder="编码/名称" clearable
              :prefix-icon="Search" style="width: 180px;" @keyup.enter="loadData"
            />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 100px;">
              <el-option label="启用" value="1" />
              <el-option label="停用" value="0" />
            </el-select>
            <el-button type="primary" @click="loadData">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openForm()">新建</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="whCode" label="仓库编码" width="130" />
        <el-table-column prop="whName" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="orgUnit" label="所属组织" width="140">
          <template #default="{ row }">{{ row.orgUnit || '-' }}</template>
        </el-table-column>
        <el-table-column prop="capacityDesc" label="容量属性" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.capacityDesc || '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">变更</el-button>
            <el-button v-if="row.status === '1'" link type="warning"
                       :disabled="row.whCode === DEFAULT_WH" @click="handleDisable(row)">停用</el-button>
            <el-button v-else link type="success" @click="handleEnable(row)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 变更弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '变更仓库' : '新建仓库'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="仓库编码">
          <el-input v-model="form.whCode" disabled :placeholder="form.id ? '' : '保存后自动生成 WH-XXXX'" />
        </el-form-item>
        <el-form-item label="名称" prop="whName">
          <el-input v-model="form.whName" maxlength="128" />
        </el-form-item>
        <el-form-item label="所属组织">
          <el-input v-model="form.orgUnit" maxlength="64" />
        </el-form-item>
        <el-form-item label="容量属性">
          <el-input v-model="form.capacityDesc" maxlength="255" placeholder="如：常温 500 托位" />
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
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getWarehousesApi, createWarehouseApi, updateWarehouseApi,
  enableWarehouseApi, disableWarehouseApi
} from '@/api/inv/warehouse'

/** 默认仓（055 回填目标），页面禁用停用入口，服务端另有校验 */
const DEFAULT_WH = 'WH-MAIN'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const query = ref({ keyword: '', status: '' })

const formVisible = ref(false)
const formRef = ref(null)
const form = ref({})
const rules = {
  whName: [{ required: true, message: '请输入仓库名称', trigger: 'blur' }]
}

async function loadData() {
  loading.value = true
  try {
    const res = await getWarehousesApi({
      keyword: query.value.keyword || undefined,
      status: query.value.status || undefined
    })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openForm(row) {
  form.value = row ? { ...row } : { whName: '', orgUnit: '', capacityDesc: '', remark: '' }
  formVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.value.id) {
      await updateWarehouseApi(form.value)
      ElMessage.success('变更已保存（编码不可改）')
    } else {
      await createWarehouseApi(form.value)
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
    `确认停用「${row.whName}」？停用后不再被新发货单与新预留选用，在途单据不受影响。`,
    '停用确认', { type: 'warning' }
  )
  await disableWarehouseApi(row.id)
  ElMessage.success('已停用')
  loadData()
}

async function handleEnable(row) {
  await enableWarehouseApi(row.id)
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
}
</style>
