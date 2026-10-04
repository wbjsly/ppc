<template>
  <div class="profit-center">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">利润中心</span>
          <div class="header-actions">
            <el-select v-model="query.legalEntityId" placeholder="法人主体" clearable filterable style="width: 200px;">
              <el-option v-for="e in legalEntities" :key="e.id" :label="`${e.code} ${e.name}`" :value="e.id" />
            </el-select>
            <el-input
              v-model="query.keyword" placeholder="编码/名称" clearable
              :prefix-icon="Search" style="width: 180px;" @keyup.enter="loadData(1)"
            />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 100px;">
              <el-option label="启用" value="1" />
              <el-option label="停用" value="0" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openForm()">新建</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="pcCode" label="编码" width="110" />
        <el-table-column prop="pcName" label="名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="legalEntityId" label="所属主体" width="200">
          <template #default="{ row }">{{ entityName(row.legalEntityId) }}</template>
        </el-table-column>
        <el-table-column prop="ownerName" label="负责人" width="100">
          <template #default="{ row }">{{ row.ownerName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="verNo" label="版本" width="70" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" :disabled="row.status === '0'" @click="openForm(row)">变更</el-button>
            <el-button link type="danger" :disabled="row.status === '0'" @click="handleDisable(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top: 14px; justify-content: flex-end;"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.size"
        :current-change="loadData"
      />
    </el-card>

    <!-- 新建 / 变更弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '变更利润中心' : '新建利润中心'" width="600px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="编码">
          <el-input v-model="form.pcCode" disabled :placeholder="form.id ? '' : '保存后自动生成 PC-XXXX'" />
        </el-form-item>
        <el-form-item label="名称" prop="pcName">
          <el-input v-model="form.pcName" maxlength="128" />
        </el-form-item>
        <el-form-item label="所属法人主体" prop="legalEntityId">
          <el-select v-model="form.legalEntityId" filterable :disabled="!!form.id" style="width: 100%;">
            <el-option v-for="e in legalEntities" :key="e.id" :label="`${e.code} ${e.name}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="form.ownerName" maxlength="64" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（含版本历史与对比） -->
    <el-drawer v-model="detailVisible" title="利润中心详情" size="620px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="编码">{{ detail.pcCode }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ detail.pcName }}</el-descriptions-item>
        <el-descriptions-item label="所属主体">{{ entityName(detail.legalEntityId) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detail.status === '1' ? 'success' : 'info'" size="small">
            {{ detail.status === '1' ? '启用' : '停用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="负责人">{{ detail.ownerName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detail.remark || '-' }}</el-descriptions-item>
        <el-descriptions-item label="当前版本">V{{ detail.verNo }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">版本历史</el-divider>
      <div class="diff-bar">
        <el-select v-model="diffFrom" placeholder="起始版本" style="width: 110px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <span>→</span>
        <el-select v-model="diffTo" placeholder="目标版本" style="width: 110px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <el-button type="primary" plain :disabled="!diffFrom || !diffTo || diffFrom === diffTo" @click="loadDiff">
          对比
        </el-button>
      </div>

      <el-table :data="versions" size="small" stripe>
        <el-table-column prop="versionNo" label="版本" width="60">
          <template #default="{ row }">V{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="opType" label="操作" width="90">
          <template #default="{ row }">{{ opMap[row.opType] || row.opType }}</template>
        </el-table-column>
        <el-table-column prop="diffSummary" label="变更摘要" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.diffSummary || '初始版本' }}</template>
        </el-table-column>
        <el-table-column prop="createBy" label="操作人" width="100" />
        <el-table-column prop="createDate" label="时间" width="155">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ') }}</template>
        </el-table-column>
      </el-table>

      <template v-if="diffFields.length">
        <el-divider content-position="left">差异对比</el-divider>
        <el-table :data="diffFields" size="small" border>
          <el-table-column prop="field" label="字段" width="140" />
          <el-table-column prop="from" label="起始值" show-overflow-tooltip />
          <el-table-column prop="to" label="目标值" show-overflow-tooltip />
        </el-table>
      </template>
      <el-empty v-else-if="diffRequested" description="两版本无差异" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getProfitCenterPageApi, getProfitCenterApi, createProfitCenterApi,
  updateProfitCenterApi, disableProfitCenterApi,
  getProfitCenterVersionsApi, getProfitCenterDiffApi
} from '@/api/mdm/profit-center'
import { getLegalEntityOptionsApi } from '@/api/mdm/legal-entity'

const opMap = { CREATE: '新建', UPDATE: '变更', DISABLE: '停用' }

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ current: 1, size: 10, keyword: '', legalEntityId: '', status: '' })
const legalEntities = ref([])

const formVisible = ref(false)
const formRef = ref(null)
const form = ref({})
const rules = {
  pcName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  legalEntityId: [{ required: true, message: '请选择法人主体', trigger: 'change' }]
}

const detailVisible = ref(false)
const detail = ref({})
const versions = ref([])
const diffFrom = ref(null)
const diffTo = ref(null)
const diffFields = ref([])
const diffRequested = ref(false)

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getProfitCenterPageApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

async function loadEntities() {
  const res = await getLegalEntityOptionsApi()
  legalEntities.value = res.data
}

function entityName(id) {
  const e = legalEntities.value.find(x => x.id === id)
  return e ? `${e.code} ${e.name}` : id
}

function openForm(row) {
  form.value = row
    ? { ...row }
    : { pcName: '', legalEntityId: query.value.legalEntityId || '', ownerName: '', remark: '' }
  formVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.value.id) {
      await updateProfitCenterApi(form.value)
      ElMessage.success('变更已生效')
    } else {
      await createProfitCenterApi(form.value)
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
    `确认停用「${row.pcName}」？若名下仍有启用中的成本中心将被阻断。`,
    '停用确认', { type: 'warning' }
  )
  await disableProfitCenterApi(row.id)
  ElMessage.success('已停用')
  loadData()
}

async function openDetail(row) {
  const res = await getProfitCenterApi(row.id)
  detail.value = res.data
  versions.value = await getProfitCenterVersionsApi(row.id).then(r => r.data)
  diffFrom.value = null
  diffTo.value = null
  diffFields.value = []
  diffRequested.value = false
  detailVisible.value = true
}

async function loadDiff() {
  const res = await getProfitCenterDiffApi(detail.value.id, diffFrom.value, diffTo.value)
  diffFields.value = res.data.fields
  diffRequested.value = true
}

onMounted(() => {
  loadData()
  loadEntities()
})
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
.diff-bar {
  margin-bottom: 10px;
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
