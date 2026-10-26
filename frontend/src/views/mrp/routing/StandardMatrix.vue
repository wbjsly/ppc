<template>
  <div class="page">
    <el-page-header content="标准工时（5.2.3）— （工序 × 工作中心）工时定额矩阵，同时表达适配关系"
                    style="margin-bottom: 8px" />

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="工序">
        <el-select v-model="query.opCode" clearable filterable placeholder="全部工序" style="width: 200px;"
                   @change="loadData">
          <el-option v-for="o in opOptions" :key="o.opCode" :label="`${o.opCode} ${o.opName}`" :value="o.opCode" />
        </el-select>
      </el-form-item>
      <el-form-item label="工作中心">
        <el-select v-model="query.wcCode" clearable filterable placeholder="全部工作中心" style="width: 200px;"
                   @change="loadData">
          <el-option v-for="w in wcOptions" :key="w.wcCode" :label="`${w.wcCode} ${w.wcName}`" :value="w.wcCode" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">查询</el-button>
        <el-button v-if="canEdit" type="success" @click="openCreate">新增定额</el-button>
      </el-form-item>
      <span class="tip">一行定额 = 一个（工序↔工作中心）适配关系 + 四类工时；路线装配 5.2.4 引用前必须先在此维护</span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="opCode" label="工序编码" width="150" />
      <el-table-column prop="wcCode" label="工作中心" width="150" />
      <el-table-column prop="setupHours" label="准备工时" width="100" />
      <el-table-column prop="runHours" label="标准工时" width="100" />
      <el-table-column prop="waitHours" label="等待工时" width="100" />
      <el-table-column prop="moveHours" label="移动工时" width="100" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEdit" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canEdit" link type="danger" size="small" @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无工时定额（先维护工序与工作中心）" />

    <!-- 新建 / 编辑弹窗 -->
    <el-dialog v-model="editorVisible" :title="editingId ? '编辑工时定额' : '新增工时定额'" width="560px"
               :close-on-click-modal="false">
      <el-form :model="form" label-width="100px" size="small">
        <el-form-item label="工序" required>
          <el-select v-model="form.opCode" filterable :disabled="!!editingId" placeholder="选择工序"
                     style="width: 100%;">
            <el-option v-for="o in opOptions" :key="o.opCode" :label="`${o.opCode} ${o.opName}`"
                       :value="o.opCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="工作中心" required>
          <el-select v-model="form.wcCode" filterable :disabled="!!editingId" placeholder="选择工作中心"
                     style="width: 100%;">
            <el-option v-for="w in wcOptions" :key="w.wcCode" :label="`${w.wcCode} ${w.wcName}`"
                       :value="w.wcCode" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="准备工时">
              <el-input-number v-model="form.setupHours" :min="0" :precision="4" controls-position="right"
                               style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="标准工时">
              <el-input-number v-model="form.runHours" :min="0" :precision="4" controls-position="right"
                               style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="等待工时">
              <el-input-number v-model="form.waitHours" :min="0" :precision="4" controls-position="right"
                               style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="移动工时">
              <el-input-number v-model="form.moveHours" :min="0" :precision="4" controls-position="right"
                               style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-alert v-if="editingId" type="info" :closable="false" show-icon
                  title="编码组合不可修改：如需调整（工序, 工作中心）组合，请删除后重建" />
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getStandardsApi, createStandardApi, updateStandardApi, deleteStandardApi,
  getOperationsApi, getWorkCentersApi
} from '@/api/mrp/routing'

// 5.2.3 标准工时：定额矩阵 CRUD；组合唯一/工时≥0/删除被引用均由服务端阻断并弹 message
const userStore = useUserStore()
const canEdit = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_PROCESS_ENG') || roles.includes('ROLE_PROCESS_MGR')
})

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const opOptions = ref([])
const wcOptions = ref([])
const query = ref({ opCode: '', wcCode: '' })

const editorVisible = ref(false)
const editingId = ref('')
const form = ref({})

async function loadData() {
  loading.value = true
  try {
    const params = {}
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    const res = await getStandardsApi(params)
    rows.value = res.data || []
  } catch (e) {
    console.warn('[standard] load failed', e)
  } finally {
    loading.value = false
  }
}

async function loadDicts() {
  const [ops, wcs] = await Promise.all([getOperationsApi({}), getWorkCentersApi({})])
  opOptions.value = ops.data || []
  wcOptions.value = wcs.data || []
}

function blankForm() {
  return { opCode: '', wcCode: '', setupHours: 0, runHours: 0, waitHours: 0, moveHours: 0 }
}

function openCreate() {
  editingId.value = ''
  form.value = blankForm()
  editorVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = {
    opCode: row.opCode, wcCode: row.wcCode,
    setupHours: Number(row.setupHours), runHours: Number(row.runHours),
    waitHours: Number(row.waitHours), moveHours: Number(row.moveHours)
  }
  editorVisible.value = true
}

async function save() {
  if (!form.value.opCode || !form.value.wcCode) {
    ElMessage.warning('工序与工作中心必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateStandardApi(editingId.value, form.value)
      ElMessage.success('定额已更新')
    } else {
      await createStandardApi(form.value)
      ElMessage.success('定额已创建')
    }
    editorVisible.value = false
    loadData()
  } catch (e) {
    console.warn('[standard] save failed', e)
  } finally {
    saving.value = false
  }
}

async function doDelete(row) {
  await ElMessageBox.confirm(
    `删除（${row.opCode} × ${row.wcCode}）的工时定额？被已发布路线引用时将被阻断`,
    '删除定额',
    { type: 'warning' }
  )
  try {
    await deleteStandardApi(row.id)
    ElMessage.success('定额已删除')
    loadData()
  } catch (e) {
    // 422（被已发布路线引用）已由拦截器弹出具体 message
    console.warn('[standard] delete failed', e)
  }
}

onMounted(async () => {
  await loadDicts()
  await loadData()
})
</script>

<style scoped>
.filter-bar {
  margin-bottom: 4px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
</style>
