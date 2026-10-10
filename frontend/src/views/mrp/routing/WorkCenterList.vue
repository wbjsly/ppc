<template>
  <div class="page">
    <el-page-header content="工作中心（5.2.2）— 资源台账：类型 / 外协供应商 / 产能三要素（负荷率分母）"
                    style="margin-bottom: 8px" />

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" clearable placeholder="编码/名称" style="width: 200px;"
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
        <el-button v-if="canEdit" type="success" @click="openCreate">新增工作中心</el-button>
      </el-form-item>
      <span class="tip">可用工时 = 日历工时 × 设备可用率 × 人员出勤率（派工负荷率公式分母）；工时费率归财务 4.6</span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="wcCode" label="编码" width="140" />
      <el-table-column prop="wcName" label="名称" min-width="150" show-overflow-tooltip />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.wcType === 'OUTSOURCED' ? 'warning' : 'info'" size="small">
            {{ row.wcType === 'OUTSOURCED' ? '外协' : '内部' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="supplierCode" label="外协供应商" width="130">
        <template #default="{ row }">{{ row.supplierCode || '-' }}</template>
      </el-table-column>
      <el-table-column prop="calHours" label="日历工时" width="96" />
      <el-table-column prop="equipAvail" label="设备%" width="80" />
      <el-table-column prop="laborAvail" label="人员%" width="80" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === '1' ? 'success' : 'danger'" size="small">
            {{ row.status === '1' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEdit" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canEdit" link :type="row.status === '1' ? 'warning' : 'success'" size="small"
                     @click="toggleStatus(row)">
            {{ row.status === '1' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无工作中心" />

    <!-- 新建 / 编辑弹窗 -->
    <el-dialog v-model="editorVisible" :title="editingId ? '编辑工作中心' : '新增工作中心'" width="620px"
               :close-on-click-modal="false">
      <el-form :model="form" label-width="110px" size="small">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="编码" required>
              <el-input v-model="form.wcCode" :disabled="!!editingId"
                        :placeholder="editingId ? '编码创建后不可修改' : '如：WC-LINE-A'" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="名称" required>
              <el-input v-model="form.wcName" placeholder="如：组装线A" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="类型">
              <el-select v-model="form.wcType" style="width: 100%;">
                <el-option label="内部" value="INTERNAL" />
                <el-option label="外协" value="OUTSOURCED" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="外协供应商" :required="form.wcType === 'OUTSOURCED'">
              <el-input v-model="form.supplierCode" :disabled="form.wcType !== 'OUTSOURCED'"
                        placeholder="外协类型必填（C-4.5-05）" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="日历工时">
              <el-input-number v-model="form.calHours" :min="0.01" :step="0.5" :precision="2"
                               controls-position="right" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="设备可用%">
              <el-input-number v-model="form.equipAvail" :min="0" :max="100" :precision="2"
                               controls-position="right" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="人员出勤%">
              <el-input-number v-model="form.laborAvail" :min="0" :max="100" :precision="2"
                               controls-position="right" style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
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
import { getWorkCentersApi, createWorkCenterApi, updateWorkCenterApi, workCenterStatusApi } from '@/api/mrp/routing'

// 5.2.2 工作中心：台账 CRUD + 启停用；外协缺供应商/非法产能由服务端 L1 阻断
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
const form = ref({})

async function loadData() {
  loading.value = true
  try {
    const params = {}
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    const res = await getWorkCentersApi(params)
    rows.value = res.data || []
  } catch (e) {
    console.warn('[workcenter] load failed', e)
  } finally {
    loading.value = false
  }
}

function blankForm() {
  return { wcCode: '', wcName: '', wcType: 'INTERNAL', supplierCode: '',
    calHours: 8, equipAvail: 100, laborAvail: 100, status: '1' }
}

function openCreate() {
  editingId.value = ''
  form.value = blankForm()
  editorVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = {
    wcCode: row.wcCode, wcName: row.wcName, wcType: row.wcType,
    supplierCode: row.supplierCode || '',
    calHours: Number(row.calHours), equipAvail: Number(row.equipAvail),
    laborAvail: Number(row.laborAvail), status: row.status
  }
  editorVisible.value = true
}

async function save() {
  if (!form.value.wcCode || !form.value.wcName) {
    ElMessage.warning('编码与名称必填')
    return
  }
  if (form.value.wcType === 'OUTSOURCED' && !form.value.supplierCode) {
    ElMessage.warning('外协工作中心必须维护外协供应商')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateWorkCenterApi(editingId.value, form.value)
      ElMessage.success('工作中心已更新')
    } else {
      await createWorkCenterApi(form.value)
      ElMessage.success('工作中心已创建')
    }
    editorVisible.value = false
    loadData()
  } catch (e) {
    console.warn('[workcenter] save failed', e)
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  const next = row.status === '1' ? '0' : '1'
  await ElMessageBox.confirm(
    next === '0' ? `停用工作中心 ${row.wcCode}？停用后不可选入新路线行` : `启用工作中心 ${row.wcCode}？`,
    next === '0' ? '停用工作中心' : '启用工作中心',
    { type: 'warning' }
  )
  try {
    await workCenterStatusApi(row.id, next)
    ElMessage.success(next === '0' ? '已停用' : '已启用')
    loadData()
  } catch (e) {
    console.warn('[workcenter] status failed', e)
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
