<template>
  <div class="page">
    <el-page-header content="工序维护（5.2.1）— 全局工序字典，编码创建后不可修改，禁止硬删除"
                    style="margin-bottom: 8px" />

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" clearable placeholder="工序编码/名称" style="width: 200px;"
                  @keyup.enter="loadData" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px;" @change="loadData">
          <el-option label="启用" value="1" />
          <el-option label="停用" value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">查询</el-button>
        <el-button v-if="canEdit" type="success" @click="openCreate">新增工序</el-button>
      </el-form-item>
      <span class="tip">停用工序不可选入新路线行（5.2.4 装配时 L1 阻断）</span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="opCode" label="工序编码" width="160" />
      <el-table-column prop="opName" label="工序名称" min-width="160" show-overflow-tooltip />
      <el-table-column prop="skillReq" label="技能要求" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === '1' ? 'success' : 'danger'" size="small">
            {{ row.status === '1' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEdit" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canEdit" link :type="row.status === '1' ? 'warning' : 'success'" size="small"
                     @click="toggleStatus(row)">
            {{ row.status === '1' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无工序" />

    <!-- 新建 / 编辑弹窗 -->
    <el-dialog v-model="editorVisible" :title="editingId ? '编辑工序' : '新增工序'" width="520px"
               :close-on-click-modal="false">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="工序编码" required>
          <el-input v-model="form.opCode" :disabled="!!editingId"
                    :placeholder="editingId ? '编码创建后不可修改' : '如：OP-ASSY-10'" />
        </el-form-item>
        <el-form-item label="工序名称" required>
          <el-input v-model="form.opName" placeholder="如：整机装配" />
        </el-form-item>
        <el-form-item label="技能要求">
          <el-input v-model="form.skillReq" type="textarea" :rows="2"
                    placeholder="自由文本；结构化技能矩阵口径待 5.4 人员匹配" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" active-value="1" inactive-value="0"
                     active-text="启用" inactive-text="停用" />
        </el-form-item>
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
import { getOperationsApi, createOperationApi, updateOperationApi, operationStatusApi } from '@/api/mrp/routing'

// 5.2.1 工序维护：字典 CRUD + 启停用；错误提示由 request 拦截器单点弹出
const userStore = useUserStore()
const canEdit = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_PROCESS_ENG') || roles.includes('ROLE_PROCESS_MGR')
})

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const query = ref({ keyword: '', status: '' })

const editorVisible = ref(false)
const editingId = ref('')
const form = ref({ opCode: '', opName: '', skillReq: '', status: '1' })

async function loadData() {
  loading.value = true
  try {
    const params = {}
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    const res = await getOperationsApi(params)
    rows.value = res.data || []
  } catch (e) {
    console.warn('[operation] load failed', e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = ''
  form.value = { opCode: '', opName: '', skillReq: '', status: '1' }
  editorVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = { opCode: row.opCode, opName: row.opName, skillReq: row.skillReq || '', status: row.status }
  editorVisible.value = true
}

async function save() {
  if (!form.value.opCode || !form.value.opName) {
    ElMessage.warning('工序编码与名称必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateOperationApi(editingId.value, form.value)
      ElMessage.success('工序已更新')
    } else {
      await createOperationApi(form.value)
      ElMessage.success('工序已创建')
    }
    editorVisible.value = false
    loadData()
  } catch (e) {
    console.warn('[operation] save failed', e)
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  const next = row.status === '1' ? '0' : '1'
  await ElMessageBox.confirm(
    next === '0' ? `停用工序 ${row.opCode}？停用后不可选入新路线行` : `启用工序 ${row.opCode}？`,
    next === '0' ? '停用工序' : '启用工序',
    { type: 'warning' }
  )
  try {
    await operationStatusApi(row.id, next)
    ElMessage.success(next === '0' ? '已停用' : '已启用')
    loadData()
  } catch (e) {
    console.warn('[operation] status failed', e)
  }
}

onMounted(loadData)
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
