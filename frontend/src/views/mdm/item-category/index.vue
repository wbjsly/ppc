<template>
  <div class="item-category">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">分类维护</span>
          <div class="header-actions">
            <el-button type="primary" :icon="Plus" @click="openForm()">新建分类</el-button>
          </div>
        </div>
      </template>

      <el-table
        :data="tree" v-loading="loading" stripe
        row-key="id" default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="categoryCode" label="分类码" width="100" />
        <el-table-column prop="categoryName" label="分类名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="itemPrefix" label="前缀" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ row.itemPrefix }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="level" label="层级" width="70" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" :disabled="row.status === '0'" @click="openForm(row)">变更</el-button>
            <el-button link type="warning" :disabled="row.status === '0'" @click="openMerge(row)">合并</el-button>
            <el-button link type="warning" :disabled="row.status === '0'" @click="openMove(row)">迁移</el-button>
            <el-button link type="danger" :disabled="row.status === '0'" @click="handleDisable(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 变更弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '变更分类' : '新建分类'" width="600px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="分类码（4位）" prop="categoryCode">
          <el-input v-model="form.categoryCode" :disabled="!!form.id" maxlength="4" placeholder="如 0006" />
          <div v-if="form.id" class="form-tip">分类码为编码规则权威，创建后锁定（BR-4.1-07）</div>
        </el-form-item>
        <el-form-item label="分类名称" prop="categoryName">
          <el-input v-model="form.categoryName" maxlength="64" />
        </el-form-item>
        <el-form-item label="编码前缀" prop="itemPrefix">
          <el-select v-model="form.itemPrefix" :disabled="!!form.id" style="width: 100%;">
            <el-option label="FG（成品）" value="FG" />
            <el-option label="RM（原材料）" value="RM" />
            <el-option label="WIP（半成品）" value="WIP" />
          </el-select>
          <div v-if="form.id" class="form-tip">前缀创建后锁定，防止存量物料编码校验漂移</div>
        </el-form-item>
        <el-form-item label="上级分类">
          <el-select v-model="form.parentId" clearable placeholder="留空 = 根节点" style="width: 100%;">
            <el-option
              v-for="p in parentCandidates" :key="p.id"
              :label="`[${p.categoryCode}] ${p.categoryName}（第${p.level}级）`"
              :value="p.id"
              :disabled="p.id === form.id"
            />
          </el-select>
          <div class="form-tip">层级最多 3 级；不可选择自身或其子孙</div>
        </el-form-item>
        <el-form-item v-if="form.id" label="变更原因" prop="changeReason">
          <el-input v-model="form.changeReason" maxlength="255" placeholder="必填，说明为何变更（≥2字）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 合并弹窗 -->
    <el-dialog v-model="mergeVisible" :title="`合并「${mergeSource?.categoryName}」`" width="560px">
      <el-form label-width="130px">
        <el-form-item label="目标分类">
          <el-select v-model="mergeTargetId" filterable placeholder="选择目标分类" style="width: 100%;">
            <el-option
              v-for="c in mergeCandidates" :key="c.id"
              :label="`[${c.categoryCode}] ${c.categoryName}（前缀 ${c.itemPrefix}）`"
              :value="c.id"
            />
          </el-select>
          <div class="form-tip">仅可选择与源分类相同编码前缀的目标</div>
        </el-form-item>
        <el-form-item label="合并原因" prop="reason">
          <el-input v-model="mergeReason" maxlength="255" placeholder="必填（≥2字）" />
        </el-form-item>
        <el-alert
          v-if="impact"
          :type="impact.samePrefix ? 'warning' : 'error'"
          show-icon :closable="false" style="margin-bottom: 8px;"
          :title="impact.samePrefix
            ? `影响提示：将改挂 ${impact.itemCount} 个物料、${impact.childCount} 个子分类；源分类将被停用`
            : '源与目标编码前缀不同，禁止合并'"
          description="物料将批量改挂并逐条生成版本快照，此操作不可撤销。"
        />
      </el-form>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button
          type="danger" :loading="saving"
          :disabled="!mergeTargetId || !mergeReason || mergeReason.trim().length < 2 || (impact && !impact.samePrefix)"
          @click="submitMerge"
        >确认合并</el-button>
      </template>
    </el-dialog>

    <!-- 迁移弹窗 -->
    <el-dialog v-model="moveVisible" :title="`迁移「${moveSource?.categoryName}」`" width="560px">
      <el-form label-width="130px">
        <el-form-item label="新上级分类">
          <el-select v-model="moveTargetParentId" clearable placeholder="留空 = 移至根" style="width: 100%;">
            <el-option
              v-for="p in moveCandidates" :key="p.id"
              :label="`[${p.categoryCode}] ${p.categoryName}（第${p.level}级）`"
              :value="p.id"
              :disabled="p.id === moveSource?.id"
            />
          </el-select>
          <div class="form-tip">仅移动节点位置，存量物料与编码不受影响</div>
        </el-form-item>
        <el-form-item label="迁移原因">
          <el-input v-model="moveReason" maxlength="255" placeholder="必填（≥2字）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moveVisible = false">取消</el-button>
        <el-button
          type="primary" :loading="saving"
          :disabled="!moveReason || moveReason.trim().length < 2"
          @click="submitMove"
        >确认迁移</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（版本历史 + 对比） -->
    <el-drawer v-model="detailVisible" title="分类详情与版本" size="620px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="分类码">{{ detail.categoryCode }}</el-descriptions-item>
        <el-descriptions-item label="分类名称">{{ detail.categoryName }}</el-descriptions-item>
        <el-descriptions-item label="编码前缀">{{ detail.itemPrefix }}</el-descriptions-item>
        <el-descriptions-item label="层级">第 {{ detail.level }} 级</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detail.status === '1' ? 'success' : 'info'" size="small">
            {{ detail.status === '1' ? '启用' : '停用' }}
          </el-tag>
        </el-descriptions-item>
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
        <el-button type="primary" plain :disabled="!diffFrom || !diffTo || diffFrom === diffTo" @click="loadDiff">对比</el-button>
      </div>

      <el-table :data="versions" size="small" stripe>
        <el-table-column prop="versionNo" label="版本" width="60">
          <template #default="{ row }">V{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="opType" label="操作" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.opType === 'MERGE'" type="warning" size="small">合并</el-tag>
            <el-tag v-else-if="row.opType === 'MOVE'" type="primary" size="small">迁移</el-tag>
            <span v-else>{{ opMap[row.opType] || row.opType }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="changeReason" label="原因" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.changeReason || '-' }}</template>
        </el-table-column>
        <el-table-column prop="diffSummary" label="摘要" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.diffSummary || '初始版本' }}</template>
        </el-table-column>
        <el-table-column prop="createDate" label="时间" width="150">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ') }}</template>
        </el-table-column>
      </el-table>

      <template v-if="diffFields.length">
        <el-divider content-position="left">差异对比</el-divider>
        <el-table :data="diffFields" size="small" border>
          <el-table-column prop="field" label="字段" width="130" />
          <el-table-column prop="from" label="起始值" show-overflow-tooltip />
          <el-table-column prop="to" label="目标值" show-overflow-tooltip />
        </el-table>
      </template>
      <el-empty v-else-if="diffRequested" description="两版本无差异" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  getCategoryTreeApi, createCategoryApi, updateCategoryApi, disableCategoryApi,
  getMergeImpactApi, mergeCategoryApi, moveCategoryApi,
  getCategoryVersionsApi, getCategoryDiffApi
} from '@/api/mdm/item-category'

const opMap = { CREATE: '新建', UPDATE: '变更', DISABLE: '停用' }

const loading = ref(false)
const saving = ref(false)
const tree = ref([])

const formVisible = ref(false)
const formRef = ref(null)
const form = ref({})
const rules = {
  categoryCode: [
    { required: true, message: '请输入 4 位分类码', trigger: 'blur' },
    { pattern: /^\d{4}$/, message: '分类码须为 4 位数字', trigger: 'blur' }
  ],
  categoryName: [{ required: true, message: '请输入分类名称', trigger: 'blur' }],
  itemPrefix: [{ required: true, message: '请选择编码前缀', trigger: 'change' }],
  changeReason: [{
    validator: (rule, value, cb) => {
      if (form.value.id && (!value || value.trim().length < 2)) cb(new Error('变更原因必填（至少 2 字）'))
      else cb()
    },
    trigger: 'blur'
  }]
}

const mergeVisible = ref(false)
const mergeSource = ref(null)
const mergeTargetId = ref('')
const mergeReason = ref('')
const impact = ref(null)

const moveVisible = ref(false)
const moveSource = ref(null)
const moveTargetParentId = ref('')
const moveReason = ref('')

const detailVisible = ref(false)
const detail = ref({})
const versions = ref([])
const diffFrom = ref(null)
const diffTo = ref(null)
const diffFields = ref([])
const diffRequested = ref(false)

const parentCandidates = computed(() => {
  const excluded = new Set()
  const mark = (n) => { excluded.add(n.id); (n.children || []).forEach(mark) }
  const self = findNode(tree.value, form.value.id)
  if (self) mark(self)
  const list = []
  const walk = (nodes) => {
    for (const n of nodes) {
      if (!excluded.has(n.id) && n.level < 3) list.push(n)
      ;(n.children || []).forEach(c => walk([c]))
    }
  }
  walk(tree.value)
  return list
})

const mergeCandidates = computed(() => {
  if (!mergeSource.value) return []
  const list = []
  const walk = (nodes) => {
    for (const n of nodes) {
      if (n.id !== mergeSource.value.id && n.status === '1') list.push(n)
      ;(n.children || []).forEach(c => walk([c]))
    }
  }
  walk(tree.value)
  return list
})

const moveCandidates = computed(() => {
  const excluded = new Set()
  const mark = (n) => { excluded.add(n.id); (n.children || []).forEach(mark) }
  if (moveSource.value) mark(moveSource.value)
  const list = []
  const walk = (nodes) => {
    for (const n of nodes) {
      if (!excluded.has(n.id) && n.level < 3) list.push(n)
      ;(n.children || []).forEach(c => walk([c]))
    }
  }
  walk(tree.value)
  return list
})

function findNode(nodes, id) {
  if (!id) return null
  for (const n of nodes || []) {
    if (n.id === id) return n
    const f = findNode(n.children, id)
    if (f) return f
  }
  return null
}

async function loadData() {
  loading.value = true
  try {
    const res = await getCategoryTreeApi()
    tree.value = res.data
  } finally {
    loading.value = false
  }
}

function openForm(row) {
  form.value = row
    ? { ...row, children: undefined, changeReason: '' }
    : { categoryCode: '', categoryName: '', itemPrefix: '', parentId: '' }
  formVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.value.id) {
      await updateCategoryApi(form.value)
      ElMessage.success('变更已生效')
    } else {
      await createCategoryApi(form.value)
      ElMessage.success('新建成功')
    }
    formVisible.value = false
    loadData()
  } catch (e) {
    // 业务错误已由 request 拦截器单点提示，此处仅吞掉 rejection 避免 unhandled 警告
  } finally {
    saving.value = false
  }
}

async function handleDisable(row) {
  try {
    await ElMessageBox.confirm(
      `确认停用「${row.categoryName}」？若有未停用子孙或被启用物料引用将被阻断。`,
      '停用确认', { type: 'warning' }
    )
    await disableCategoryApi(row.id)
    ElMessage.success('已停用')
    loadData()
  } catch (e) {
    // 用户取消（reject）与业务阻断（拦截器已提示）统一在此吞掉
  }
}

async function openMerge(row) {
  mergeSource.value = row
  mergeTargetId.value = ''
  mergeReason.value = ''
  impact.value = null
  mergeVisible.value = true
}

async function loadImpact() {
  if (!mergeSource.value || !mergeTargetId.value) return
  const res = await getMergeImpactApi(mergeSource.value.id, mergeTargetId.value)
  impact.value = res.data
}

async function submitMerge() {
  saving.value = true
  try {
    const large = impact.value && impact.value.itemCount > 5000
    if (large) {
      await ElMessageBox.confirm(
        `受影响物料 ${impact.value.itemCount} 条超过 5000，确认继续批量改挂？`,
        '大规模合并确认', { type: 'warning' }
      )
    }
    await mergeCategoryApi(mergeSource.value.id, mergeTargetId.value, mergeReason.value.trim(), large)
    ElMessage.success('合并完成：子分类与存量物料已改挂，源分类已停用')
    mergeVisible.value = false
    loadData()
  } catch (e) {
    // 取消/业务阻断已单点提示
  } finally {
    saving.value = false
  }
}

function openMove(row) {
  moveSource.value = row
  moveTargetParentId.value = ''
  moveReason.value = ''
  moveVisible.value = true
}

async function submitMove() {
  saving.value = true
  try {
    await moveCategoryApi(moveSource.value.id, moveTargetParentId.value || '', moveReason.value.trim())
    ElMessage.success('迁移完成：存量物料与编码不受影响')
    moveVisible.value = false
    loadData()
  } catch (e) {
    // 业务阻断已单点提示
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  detail.value = row
  versions.value = await getCategoryVersionsApi(row.id).then(r => r.data)
  diffFrom.value = null
  diffTo.value = null
  diffFields.value = []
  diffRequested.value = false
  detailVisible.value = true
}

async function loadDiff() {
  const res = await getCategoryDiffApi(detail.value.id, diffFrom.value, diffTo.value)
  diffFields.value = res.data.fields
  diffRequested.value = true
}

onMounted(loadData)

watch(mergeTargetId, () => loadImpact())
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; }
.form-tip { font-size: 12px; color: #909399; line-height: 1.5; margin-top: 2px; }
.diff-bar { margin-bottom: 10px; display: flex; gap: 8px; align-items: center; }
</style>
