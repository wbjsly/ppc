<template>
  <div class="org-unit">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">组织单元</span>
          <div class="header-actions">
            <el-select v-model="query.legalEntityId" placeholder="法人主体" clearable filterable style="width: 200px;">
              <el-option v-for="e in legalEntities" :key="e.id" :label="`${e.code} ${e.name}`" :value="e.id" />
            </el-select>
            <el-select v-model="query.ouType" placeholder="类型" clearable style="width: 130px;">
              <el-option v-for="(label, key) in typeMap" :key="key" :label="label" :value="key" />
            </el-select>
            <el-input
              v-model="query.keyword" placeholder="编码/名称" clearable
              :prefix-icon="Search" style="width: 170px;" @keyup.enter="loadData"
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

      <el-table
        :data="tree" v-loading="loading" stripe
        row-key="id" default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="ouCode" label="编码" width="160" />
        <el-table-column prop="ouName" label="名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="ouType" label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="typeTagType[row.ouType]">{{ typeMap[row.ouType] || row.ouType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="treeLevel" label="层级" width="70" />
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
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" :disabled="row.status === '0'" @click="openForm(row)">变更</el-button>
            <el-button link type="danger" :disabled="row.status === '0'" @click="handleDisable(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 变更弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '变更组织单元' : '新建组织单元'" width="640px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="单元编码">
          <el-input v-model="form.ouCode" disabled :placeholder="form.id ? '' : '保存后按类型自动生成 OU-XXX-NN'" />
        </el-form-item>
        <el-form-item label="名称" prop="ouName">
          <el-input v-model="form.ouName" maxlength="128" />
        </el-form-item>
        <el-form-item label="所属法人主体" prop="legalEntityId">
          <el-select v-model="form.legalEntityId" filterable style="width: 100%;">
            <el-option v-for="e in legalEntities" :key="e.id" :label="`${e.code} ${e.name}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型" prop="ouType">
          <el-select v-model="form.ouType" :disabled="!!form.id" style="width: 100%;">
            <el-option v-for="(label, key) in typeMap" :key="key" :label="label" :value="key" />
          </el-select>
          <div v-if="form.id" class="form-tip">类型与编码前缀绑定，创建后不可修改</div>
        </el-form-item>
        <el-form-item label="上级组织单元">
          <el-select v-model="form.parentId" clearable placeholder="留空 = 根节点" style="width: 100%;">
            <el-option
              v-for="p in parentCandidates" :key="p.id"
              :label="`[${p.ouCode}] ${p.ouName}（第${p.treeLevel}级）`"
              :value="p.id"
              :disabled="p.id === form.id"
            />
          </el-select>
          <div class="form-tip">层级最多 3 级；不可选择自身或其子孙作为上级；类型不受层级限制</div>
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
    <el-drawer v-model="detailVisible" title="组织单元详情" size="620px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="编码">{{ detail.ouCode }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ detail.ouName }}</el-descriptions-item>
        <el-descriptions-item label="所属主体">{{ entityName(detail.legalEntityId) }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag size="small" :type="typeTagType[detail.ouType]">{{ typeMap[detail.ouType] || detail.ouType }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="层级">第 {{ detail.treeLevel }} 级</el-descriptions-item>
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
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getOrgUnitTreeApi, createOrgUnitApi, updateOrgUnitApi,
  disableOrgUnitApi, getOrgUnitVersionsApi, getOrgUnitDiffApi
} from '@/api/mdm/org-unit'
import { getLegalEntityOptionsApi } from '@/api/mdm/legal-entity'

const typeMap = { FACTORY: '工厂', WAREHOUSE: '仓库', PROC: '采购组织', SALE: '销售组织', STORE: '门店', RD: '研发中心' }
const typeTagType = { FACTORY: 'primary', WAREHOUSE: undefined, PROC: 'success', SALE: 'warning', STORE: 'danger', RD: 'info' }
const opMap = { CREATE: '新建', UPDATE: '变更', DISABLE: '停用' }

const loading = ref(false)
const saving = ref(false)
const tree = ref([])
const query = ref({ legalEntityId: '', ouType: '', keyword: '', status: '' })
const legalEntities = ref([])

const formVisible = ref(false)
const formRef = ref(null)
const form = ref({})
const rules = {
  ouName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  legalEntityId: [{ required: true, message: '请选择法人主体', trigger: 'change' }],
  ouType: [{ required: true, message: '请选择类型', trigger: 'change' }]
}

const detailVisible = ref(false)
const detail = ref({})
const versions = ref([])
const diffFrom = ref(null)
const diffTo = ref(null)
const diffFields = ref([])
const diffRequested = ref(false)

/** 可选上级：当前树内启用且非自身子孙（深度 3 节点无法再挂子节点，排除） */
const parentCandidates = computed(() => {
  const excluded = new Set()
  const mark = (node) => {
    excluded.add(node.id)
    ;(node.children || []).forEach(mark)
  }
  const self = tree.value.find(n => n.id === form.value.id)
  if (self) mark(self)
  const list = []
  const walk = (nodes) => {
    for (const n of nodes) {
      if (!excluded.has(n.id) && n.treeLevel < 3 && n.status === '1') list.push(n)
      ;(n.children || []).forEach(c => walk([c]))
    }
  }
  walk(tree.value)
  return list
})

async function loadData() {
  loading.value = true
  try {
    const res = await getOrgUnitTreeApi(query.value)
    tree.value = res.data
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
    ? { ...row, children: undefined }
    : {
        ouName: '', legalEntityId: query.value.legalEntityId || '',
        ouType: 'FACTORY', parentId: '', ownerName: '', remark: ''
      }
  formVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.value.id) {
      await updateOrgUnitApi(form.value)
      ElMessage.success('变更已生效')
    } else {
      await createOrgUnitApi(form.value)
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
    `确认停用「${row.ouName}」？若有未停用的子孙节点将被阻断。`,
    '停用确认', { type: 'warning' }
  )
  await disableOrgUnitApi(row.id)
  ElMessage.success('已停用')
  loadData()
}

async function openDetail(row) {
  versions.value = await getOrgUnitVersionsApi(row.id).then(r => r.data)
  detail.value = row
  diffFrom.value = null
  diffTo.value = null
  diffFields.value = []
  diffRequested.value = false
  detailVisible.value = true
}

async function loadDiff() {
  const res = await getOrgUnitDiffApi(detail.value.id, diffFrom.value, diffTo.value)
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
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 2px;
}
.diff-bar {
  margin-bottom: 10px;
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
