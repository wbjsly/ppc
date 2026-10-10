<template>
  <div class="page">
    <el-page-header content="版本发布（5.1.3）— 提交审核 → 工艺主管签署 → 发布生效；可手动废止"
                    style="margin-bottom: 8px" />

    <!-- 审批待办（BomPublish，工艺主管可签） -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">我的发布待办</span>
        <el-button size="small" @click="loadTodo">刷新</el-button>
        <span class="tip">仅显示当前角色可签的 BomPublish 待办；驳回意见须 ≥2 字，驳回后回到草稿可改</span>
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
            <el-button link type="info" size="small" @click="openByBizId(row)">BOM 详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!todoLoading && todos.length === 0" description="暂无可签发布待办" :image-size="60" />
    </div>

    <!-- BOM 版本列表 -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">版本列表</span>
        <el-select v-model="query.status" style="width: 150px;" size="small" @change="loadData">
          <el-option label="草稿（可提交）" value="DRAFT" />
          <el-option label="待审核" value="PENDING" />
          <el-option label="全部状态" value="" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已变更" value="REVISED" />
          <el-option label="已废止" value="OBSOLETE" />
        </el-select>
        <el-input v-model="query.parentItemCode" clearable placeholder="父项编码" size="small"
                  style="width: 170px; margin-left: 8px;" @keyup.enter="loadData" />
        <el-button size="small" type="primary" @click="loadData">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column prop="parentItemCode" label="父项编码" width="130" />
        <el-table-column prop="parentItemName" label="父项名称" min-width="130" show-overflow-tooltip />
        <el-table-column label="版本" width="80">
          <template #default="{ row }">V{{ row.versionLabel }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布人/时间" width="170">
          <template #default="{ row }">{{ row.publishBy || '-' }} / {{ fmtTime(row.publishAt) }}</template>
        </el-table-column>
        <el-table-column prop="rejectReason" label="最近驳回" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canEng && row.status === 'DRAFT'" link type="primary" size="small"
                       @click="doSubmit(row)">提交审核</el-button>
            <el-button v-if="canMgr && (row.status === 'PUBLISHED' || row.status === 'REVISED')" link
                       type="danger" size="small" @click="doObsolete(row)">废止</el-button>
            <el-button link type="info" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && rows.length === 0" description="暂无 BOM 版本" :image-size="60" />
    </div>

    <!-- 通过意见 -->
    <el-dialog v-model="passVisible" title="发布审批 - 通过" width="480px">
      <el-alert v-if="currentTodo" :title="currentTodo.title" type="info" :closable="false"
                style="margin-bottom: 12px;" />
      <el-input v-model="passOpinion" type="textarea" :rows="3" placeholder="审批意见（可空，默认同意）" />
      <template #footer>
        <el-button @click="passVisible = false">取消</el-button>
        <el-button type="success" :loading="saving" @click="doPass">确认通过（发布生效）</el-button>
      </template>
    </el-dialog>

    <!-- 驳回原因 -->
    <el-dialog v-model="rejectVisible" title="发布审批 - 驳回" width="480px">
      <el-alert v-if="currentTodo" :title="currentTodo.title" type="warning" :closable="false"
                style="margin-bottom: 12px;" />
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="500"
                placeholder="驳回原因（必填，至少 2 字；驳回后回到草稿）" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="BOM 版本详情" size="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="父项">{{ detail.parentItemCode }} {{ detail.parentItemName }}</el-descriptions-item>
          <el-descriptions-item label="版本">V{{ detail.versionLabel }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusText(detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="生效/失效">{{ detail.effectiveDate || '-' }} ~ {{ detail.expiryDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发布人">{{ detail.publishBy || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ fmtTime(detail.publishAt) }}</el-descriptions-item>
          <el-descriptions-item label="变更原因">{{ detail.changeReason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="驳回意见">{{ detail.rejectReason || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.items || []" size="small" border style="margin-top: 10px;">
          <el-table-column prop="lineNo" label="#" width="44" />
          <el-table-column prop="itemCode" label="子项编码" width="130" />
          <el-table-column prop="itemName" label="子项名称" min-width="120" show-overflow-tooltip />
          <el-table-column prop="qty" label="用量" width="76" />
          <el-table-column prop="lossRate" label="损耗率%" width="80" />
          <el-table-column prop="uom" label="单位" width="66" />
          <el-table-column label="替代料" min-width="140">
            <template #default="{ row }">
              <template v-if="(row.substitutes || []).length">
                <el-tag v-for="s in row.substitutes" :key="s.substituteItemCode" size="small" type="warning"
                        style="margin-right: 4px;">
                  {{ s.substituteItemCode }}×{{ s.ratio }}(P{{ s.priority }})
                </el-tag>
              </template>
              <span v-else>-</span>
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
import { useUserStore } from '@/store/user'
import { getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi } from '@/api/qms/approval'
import { getBomsApi, getBomDetailApi, submitBomApi, obsoleteBomApi } from '@/api/mrp/bom'

// 5.1.3 版本发布：草稿提交（挂 BomPublish 审批）→ 主管待办签署（底座 ROLE_REQUIRED 兜底）
// → 通过即发布（回调同事务）/ 驳回回草稿；废止为 MGR 手动动作
const BIZ_PUBLISH = 'BomPublish'
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const canEng = computed(() => roles.value.includes('ROLE_ADMIN') || roles.value.includes('ROLE_PROCESS_ENG'))
const canMgr = computed(() => roles.value.includes('ROLE_ADMIN') || roles.value.includes('ROLE_PROCESS_MGR'))

const loading = ref(false)
const todoLoading = ref(false)
const saving = ref(false)
const rows = ref([])
const todos = ref([])
const query = ref({ status: 'DRAFT', parentItemCode: '' })

const passVisible = ref(false)
const rejectVisible = ref(false)
const detailVisible = ref(false)
const currentTodo = ref(null)
const detail = ref(null)
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
    const res = await getBomsApi(params)
    rows.value = res.data || []
  } catch (e) {
    console.warn('[bom-publish] load failed', e)
  } finally {
    loading.value = false
  }
}

async function loadTodo() {
  todoLoading.value = true
  try {
    const res = await getQmsApprovalTodoApi()
    const all = res.data || []
    // 仅 BomPublish；ESCALATE 通知节点不可签，剔除（freeze-approve 同口径）
    todos.value = all.filter(t => t.bizType === BIZ_PUBLISH && t.kind !== 'ESCALATE')
  } catch (e) {
    console.warn('[bom-publish] todo failed', e)
  } finally {
    todoLoading.value = false
  }
}

async function doSubmit(row) {
  await ElMessageBox.confirm(`提交 V${row.versionLabel}（${row.parentItemCode}）进入审核？`, '提交审核', {
    type: 'info'
  })
  saving.value = true
  try {
    await submitBomApi(row.id)
    ElMessage.success('已提交，等待工艺主管签署')
    loadData()
  } catch (e) {
    console.warn('[bom-publish] submit failed', e)
  } finally {
    saving.value = false
  }
}

async function doObsolete(row) {
  await ElMessageBox.confirm(
    `确认废止 V${row.versionLabel}（${row.parentItemCode}）？废止为终态，不可再引用。`, '手动废止',
    { type: 'warning' }
  )
  saving.value = true
  try {
    await obsoleteBomApi(row.id)
    ElMessage.success('已废止')
    loadData()
  } catch (e) {
    console.warn('[bom-publish] obsolete failed', e)
  } finally {
    saving.value = false
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
    ElMessage.success('已通过，BOM 发布生效')
    passVisible.value = false
    loadTodo()
    loadData()
  } catch (e) {
    console.warn('[bom-publish] pass failed', e)
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
    ElMessage.success('已驳回，版本回到草稿')
    rejectVisible.value = false
    loadTodo()
    loadData()
  } catch (e) {
    console.warn('[bom-publish] reject failed', e)
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  const res = await getBomDetailApi(row.id)
  detail.value = res.data
  detailVisible.value = true
}

async function openByBizId(todoRow) {
  try {
    const res = await getBomDetailApi(todoRow.bizId)
    detail.value = res.data
    detailVisible.value = true
  } catch (e) {
    console.warn('[bom-publish] detail by bizId failed', e)
  }
}

onMounted(() => {
  loadData()
  loadTodo()
})
</script>

<style scoped>
.section {
  margin-bottom: 14px;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.section-title {
  font-weight: 600;
  font-size: 14px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
</style>
