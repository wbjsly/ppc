<template>
  <div class="page">
    <el-page-header content="路线装配（5.2.4）— 产品 → 有序工序序列；版本化 + 审批发布，同产品唯一在途"
                    style="margin-bottom: 8px" />

    <!-- 审批待办（RoutingPublish，工艺主管可签；工艺管理无独立发布菜单，签署落此处） -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">我的发布待办</span>
        <el-button size="small" @click="loadTodo">刷新</el-button>
        <span class="tip">仅显示当前角色可签的 RoutingPublish 待办；驳回后回到草稿可改</span>
      </div>
      <el-table :data="todos" v-loading="todoLoading" border stripe size="small">
        <el-table-column prop="apprNo" label="审批单号" width="150" />
        <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="applyBy" label="申请人" width="110" />
        <el-table-column label="申请时间" width="155">
          <template #default="{ row }">{{ fmtTime(row.applyDate) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="success" size="small" @click="openPass(row)">通过</el-button>
            <el-button link type="danger" size="small" @click="openReject(row)">驳回</el-button>
            <el-button link type="info" size="small" @click="openByBizId(row)">路线详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!todoLoading && todos.length === 0" description="暂无可签发布待办" :image-size="60" />
    </div>

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 130px;" @change="loadData">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审核" value="PENDING" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已变更" value="REVISED" />
          <el-option label="已废止" value="OBSOLETE" />
        </el-select>
      </el-form-item>
      <el-form-item label="产品">
        <el-input v-model="query.itemCode" clearable placeholder="产品编码/名称（留空看全部版本历史）"
                  style="width: 260px;" @keyup.enter="loadData" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">查询</el-button>
        <el-button v-if="canEdit" type="success" @click="openCreate">新建路线</el-button>
      </el-form-item>
      <span class="tip">装配前置：（工序 × 工作中心）须先在 5.2.3 维护工时定额，否则保存被 L1 阻断</span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="itemCode" label="产品编码" width="150" />
      <el-table-column prop="itemName" label="产品名称" min-width="150" show-overflow-tooltip />
      <el-table-column label="版本" width="80">
        <template #default="{ row }">V{{ row.versionLabel }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="changeReason" label="变更原因" min-width="140" show-overflow-tooltip />
      <el-table-column prop="publishBy" label="发布人" width="110">
        <template #default="{ row }">{{ row.publishBy || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEdit && row.status === 'DRAFT'" link type="primary" size="small"
                     @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canEdit && row.status === 'DRAFT'" link type="warning" size="small"
                     @click="doSubmit(row)">提交</el-button>
          <el-button v-if="canEdit && row.status === 'PUBLISHED'" link type="warning" size="small"
                     @click="openChange(row)">变更</el-button>
          <el-button link type="info" size="small" @click="openDetail(row)">详情</el-button>
          <el-button v-if="canMgr && (row.status === 'PUBLISHED' || row.status === 'REVISED')"
                     link type="danger" size="small" @click="doObsolete(row)">废止</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无工艺路线版本" />

    <!-- 装配弹窗 -->
    <el-dialog v-model="editorVisible" :title="editingId ? `编辑路线 V${editingVersion}` : '新建路线草稿'"
               width="1080px" :close-on-click-modal="false">
      <el-form :model="head" label-width="90px" size="small">
        <el-form-item label="产品" required>
          <el-select v-model="head.itemCode" filterable remote :remote-method="searchProducts"
                     :loading="productLoading" :disabled="!!editingId" placeholder="搜索产品物料（仅启用）"
                     style="width: 480px;" @change="onProductChange">
            <el-option v-for="it in productOptions" :key="it.itemCode"
                       :label="`${it.itemCode} ${it.itemName}`" :value="it.itemCode" />
          </el-select>
        </el-form-item>
      </el-form>

      <div class="ops-editor">
        <div class="ops-head">
          <span>工序序列（纯串行，序号自动 10/20/30…）</span>
          <el-button v-if="canEdit" size="small" type="success" @click="addOpRow">添加工序</el-button>
        </div>
        <el-table :data="ops" size="small" border>
          <el-table-column label="#" width="56">
            <template #default="{ $index }">{{ ($index + 1) * 10 }}</template>
          </el-table-column>
          <el-table-column label="工序" min-width="220">
            <template #default="{ row }">
              <el-select v-model="row.opCode" filterable placeholder="选择工序" style="width: 100%;"
                         @change="onOpChange(row)">
                <el-option v-for="o in opOptions" :key="o.opCode"
                           :label="`${o.opCode} ${o.opName}`" :value="o.opCode"
                           :disabled="o.status !== '1'" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="工作中心" min-width="220">
            <template #default="{ row }">
              <el-select v-model="row.wcCode" filterable placeholder="选择工作中心" style="width: 100%;"
                         @change="checkStandard(row)">
                <el-option v-for="w in wcOptions" :key="w.wcCode"
                           :label="`${w.wcCode} ${w.wcName}`" :value="w.wcCode"
                           :disabled="w.status !== '1'" />
              </el-select>
              <div v-if="row._noStandard" class="std-missing">
                ⚠ 该（工序 × 工作中心）未维护工时定额——保存将被阻断，请先在 5.2.3 维护
              </div>
            </template>
          </el-table-column>
          <el-table-column label="提前期(天)" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.leadTime" :min="0" :precision="2" :controls="false"
                               style="width: 100%;" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="ops.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 发布审批 - 通过 -->
    <el-dialog v-model="passVisible" title="发布审批 - 通过" width="480px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 8px;"
                title="通过后路线即刻发布生效（同事务回调），同产品旧版本自动置为已变更" />
      <el-input v-model="passOpinion" type="textarea" :rows="3" placeholder="审批意见（可空，默认同意）" />
      <template #footer>
        <el-button @click="passVisible = false">取消</el-button>
        <el-button type="success" :loading="saving" @click="doPass">通过并发布</el-button>
      </template>
    </el-dialog>

    <!-- 发布审批 - 驳回 -->
    <el-dialog v-model="rejectVisible" title="发布审批 - 驳回" width="480px">
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="500"
                show-word-limit placeholder="驳回原因（至少 2 字，退回草稿后可见）" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doReject">驳回</el-button>
      </template>
    </el-dialog>

    <!-- 发起变更弹窗 -->
    <el-dialog v-model="changeVisible" title="发起 BOM 式变更（克隆新版本，源头不动）" width="520px"
               :close-on-click-modal="false">
      <el-form label-width="100px" size="small">
        <el-form-item label="来源版本">
          <span>{{ changeRow?.itemCode }} V{{ changeRow?.versionLabel }}（已发布）</span>
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-input v-model="changeReason" type="textarea" :rows="3" placeholder="必填，留痕于新版本" />
        </el-form-item>
        <el-form-item label="主版本升级">
          <el-switch v-model="upgradeMajor" active-text="升级（V1.x → V2.0）" inactive-text="次版本 +1（V1.0 → V1.1）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doChange">生成变更草稿</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（含版本历史留痕） -->
    <el-drawer v-model="detailVisible" title="工艺路线详情" size="680px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="产品">{{ detail.itemCode }} {{ detail.itemName }}</el-descriptions-item>
          <el-descriptions-item label="版本">V{{ detail.versionLabel }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="变更来源">{{ detail.changeFromId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="变更原因">{{ detail.changeReason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="驳回意见">{{ detail.rejectReason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发布人">{{ detail.publishBy || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ fmtTime(detail.publishAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.ops || []" size="small" border style="margin-top: 10px;">
          <el-table-column prop="opSeq" label="序号" width="60" />
          <el-table-column prop="opCode" label="工序" width="130">
            <template #default="{ row }">{{ row.opCode }} {{ row.opName }}</template>
          </el-table-column>
          <el-table-column prop="wcCode" label="工作中心" width="130">
            <template #default="{ row }">{{ row.wcCode }} {{ row.wcName }}</template>
          </el-table-column>
          <el-table-column prop="leadTime" label="提前期" width="86" />
          <el-table-column label="标准/准备/等待/移动(工时)" min-width="180">
            <template #default="{ row }">
              <template v-if="row.standard">
                {{ row.standard.runHours }} / {{ row.standard.setupHours }} /
                {{ row.standard.waitHours }} / {{ row.standard.moveHours }}
              </template>
              <span v-else class="std-missing">未维护定额</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { useUserStore } from '@/store/user'
import { getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi } from '@/api/qms/approval'
import {
  getRoutingsApi, getRoutingDetailApi, createRoutingApi, saveRoutingDraftApi,
  changeRoutingApi, submitRoutingApi, obsoleteRoutingApi,
  getOperationsApi, getWorkCentersApi, getStandardsApi
} from '@/api/mrp/routing'

// 5.2.4 路线装配：列表（按产品即版本历史）+ 装配/编辑 + 提交审批 + 变更克隆 + 废止
const userStore = useUserStore()
const canEdit = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_PROCESS_ENG') || roles.includes('ROLE_PROCESS_MGR')
})
const canMgr = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_PROCESS_MGR')
})

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const query = ref({ status: '', itemCode: '' })

const opOptions = ref([])
const wcOptions = ref([])
const standards = ref(new Set())

const editorVisible = ref(false)
const editingId = ref('')
const editingVersion = ref('')
const head = ref({ itemCode: '', itemName: '' })
const ops = ref([])
const productOptions = ref([])
const productLoading = ref(false)

const changeVisible = ref(false)
const changeRow = ref(null)
const changeReason = ref('')
const upgradeMajor = ref(false)

const detailVisible = ref(false)
const detail = ref(null)

// 发布审批待办（RoutingPublish；工艺管理无独立发布菜单，签署落本页）
const BIZ_PUBLISH = 'RoutingPublish'
const todoLoading = ref(false)
const todos = ref([])
const passVisible = ref(false)
const rejectVisible = ref(false)
const currentTodo = ref(null)
const passOpinion = ref('')
const rejectReason = ref('')

function statusText(s) {
  return { DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REVISED: '已变更', OBSOLETE: '已废止' }[s] || s
}
function statusTag(s) {
  return { DRAFT: 'info', PENDING: 'warning', PUBLISHED: 'success', REVISED: 'info', OBSOLETE: 'danger' }[s] || 'info'
}
function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadData() {
  loading.value = true
  try {
    const params = {}
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    const res = await getRoutingsApi(params)
    rows.value = res.data || []
  } catch (e) {
    console.warn('[routing] load failed', e)
  } finally {
    loading.value = false
  }
}

async function loadDicts() {
  const [opsRes, wcsRes, stdRes] = await Promise.all([
    getOperationsApi({}), getWorkCentersApi({}), getStandardsApi({})
  ])
  opOptions.value = opsRes.data || []
  wcOptions.value = wcsRes.data || []
  standards.value = new Set((stdRes.data || []).map(s => `${s.opCode}|${s.wcCode}`))
}

async function searchProducts(kw) {
  productLoading.value = true
  try {
    const res = await request.get('/mdm/items', {
      params: { current: 1, size: 20, keyword: kw || '', status: '1' }
    })
    productOptions.value = res.data.records || []
  } finally {
    productLoading.value = false
  }
}

function onProductChange(code) {
  const it = productOptions.value.find(x => x.itemCode === code)
  head.value.itemName = it ? it.itemName : ''
}

function addOpRow() {
  ops.value.push({ opCode: '', wcCode: '', leadTime: null, _noStandard: false })
}

function onOpChange(row) {
  const o = opOptions.value.find(x => x.opCode === row.opCode)
  if (o) row.opName = o.opName
  checkStandard(row)
}

/** 前端预检：定额矩阵缺行 → 行标红（后端才是权威，保存仍会被 L1 阻断） */
function checkStandard(row) {
  row._noStandard = !!(row.opCode && row.wcCode && !standards.value.has(`${row.opCode}|${row.wcCode}`))
}

function openCreate() {
  editingId.value = ''
  editingVersion.value = ''
  head.value = { itemCode: '', itemName: '' }
  ops.value = []
  editorVisible.value = true
}

async function openEdit(row) {
  const res = await getRoutingDetailApi(row.id)
  editingId.value = row.id
  editingVersion.value = row.versionLabel
  head.value = { itemCode: res.data.itemCode, itemName: res.data.itemName }
  ops.value = (res.data.ops || []).map(o => ({
    opCode: o.opCode, opName: o.opName, wcCode: o.wcCode, wcName: o.wcName,
    leadTime: o.leadTime === null ? null : Number(o.leadTime),
    _noStandard: !standards.value.has(`${o.opCode}|${o.wcCode}`)
  }))
  editorVisible.value = true
}

function payload() {
  return {
    head: { ...head.value },
    ops: ops.value.map(o => ({ opCode: o.opCode, wcCode: o.wcCode, leadTime: o.leadTime }))
  }
}

async function save() {
  if (!head.value.itemCode) {
    ElMessage.warning('请选择产品物料')
    return
  }
  if (!ops.value.length) {
    ElMessage.warning('至少一道工序')
    return
  }
  if (ops.value.some(o => !o.opCode || !o.wcCode)) {
    ElMessage.warning('每行必须选择工序与工作中心')
    return
  }
  const missing = ops.value.filter(o => o._noStandard)
  if (missing.length) {
    ElMessage.warning(`有 ${missing.length} 行（工序 × 工作中心）未维护工时定额，请先在 5.2.3 标准工时页维护`)
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await saveRoutingDraftApi(editingId.value, payload())
      ElMessage.success('草稿已保存')
    } else {
      const res = await createRoutingApi(payload())
      ElMessage.success(`已创建草稿 V${res.data.versionLabel}`)
    }
    editorVisible.value = false
    loadData()
  } catch (e) {
    // 422（唯一在途/无定额/停用等）已由拦截器弹出具体 message
    console.warn('[routing] save failed', e)
  } finally {
    saving.value = false
  }
}

async function doSubmit(row) {
  await ElMessageBox.confirm(`提交 ${row.itemCode} V${row.versionLabel} 发布审核？（工艺主管签核）`, '提交审核', {
    type: 'info'
  })
  saving.value = true
  try {
    await submitRoutingApi(row.id)
    ElMessage.success('已提交，等待工艺主管审核')
    loadData()
  } catch (e) {
    console.warn('[routing] submit failed', e)
  } finally {
    saving.value = false
  }
}

function openChange(row) {
  changeRow.value = row
  changeReason.value = ''
  upgradeMajor.value = false
  changeVisible.value = true
}

async function doChange() {
  if (!changeReason.value || !changeReason.value.trim()) {
    ElMessage.warning('变更原因必填')
    return
  }
  saving.value = true
  try {
    const res = await changeRoutingApi(changeRow.value.id, {
      changeReason: changeReason.value.trim(),
      upgradeMajor: upgradeMajor.value
    })
    ElMessage.success(`已生成变更草稿 V${res.data.versionLabel}`)
    changeVisible.value = false
    loadData()
  } catch (e) {
    console.warn('[routing] change failed', e)
  } finally {
    saving.value = false
  }
}

async function doObsolete(row) {
  await ElMessageBox.confirm(`废止 ${row.itemCode} V${row.versionLabel}？废止后不可恢复`, '废止路线', {
    type: 'warning'
  })
  try {
    await obsoleteRoutingApi(row.id)
    ElMessage.success('已废止')
    loadData()
  } catch (e) {
    console.warn('[routing] obsolete failed', e)
  }
}

async function openDetail(row) {
  const res = await getRoutingDetailApi(row.id)
  detail.value = res.data
  detailVisible.value = true
}

async function loadTodo() {
  todoLoading.value = true
  try {
    const res = await getQmsApprovalTodoApi()
    const all = res.data || []
    // 仅 RoutingPublish；ESCALATE 通知节点不可签，剔除（BomPublish 同口径）
    todos.value = all.filter(t => t.bizType === BIZ_PUBLISH && t.kind !== 'ESCALATE')
  } catch (e) {
    console.warn('[routing] todo failed', e)
  } finally {
    todoLoading.value = false
  }
}

function openPass(row) {
  currentTodo.value = row
  passOpinion.value = ''
  passVisible.value = true
}

async function doPass() {
  saving.value = true
  try {
    await passQmsApprovalApi(currentTodo.value.taskId, passOpinion.value.trim())
    ElMessage.success('已通过，路线发布生效')
    passVisible.value = false
    loadTodo()
    loadData()
  } catch (e) {
    console.warn('[routing] pass failed', e)
  } finally {
    saving.value = false
  }
}

function openReject(row) {
  currentTodo.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function doReject() {
  const reason = (rejectReason.value || '').trim()
  if (reason.length < 2) {
    ElMessage.warning('驳回原因至少 2 字')
    return
  }
  saving.value = true
  try {
    await rejectQmsApprovalApi(currentTodo.value.taskId, reason)
    ElMessage.success('已驳回，路线退回草稿')
    rejectVisible.value = false
    loadTodo()
    loadData()
  } catch (e) {
    console.warn('[routing] reject failed', e)
  } finally {
    saving.value = false
  }
}

async function openByBizId(row) {
  if (row.bizId) {
    const res = await getRoutingDetailApi(row.bizId)
    detail.value = res.data
    detailVisible.value = true
  }
}

onMounted(async () => {
  await loadDicts()
  await Promise.all([loadData(), loadTodo()])
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
.section {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.section-title {
  font-weight: 600;
  color: #303133;
  font-size: 13px;
}
.ops-editor {
  margin-top: 4px;
}
.ops-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
  color: #606266;
  font-size: 13px;
}
.std-missing {
  color: #f56c6c;
  font-size: 12px;
  line-height: 1.4;
  margin-top: 2px;
}
</style>
