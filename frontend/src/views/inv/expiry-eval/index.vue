<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="质量评估（4.10.3）：锁定批次处置——报废 / 让步放行 / 转质量冻结"
      description="质量管理员对锁定批次手工发起；判定三分支：报废（生成报废单，质量经理签）→ 走 4.5.4；让步放行（必填放行有效期，质量经理签）→ 写豁免放行；转质量冻结（调 4.9 冻结链，即刻关闭）。解除锁定唯一入口=本页放行。" />

    <el-tabs v-model="tab">
      <!-- 评估单列表 -->
      <el-tab-pane label="评估单" name="list">
        <div class="toolbar">
          <el-select v-model="filters.status" placeholder="状态" clearable style="width: 140px"
            @change="loadPage(1)">
            <el-option label="待判定" value="PENDING_EVAL" />
            <el-option label="审批中" value="PENDING_APPR" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
          <el-input v-model="filters.keyword" placeholder="单号/批次/物料" clearable style="width: 180px"
            @clear="loadPage(1)" @keyup.enter="loadPage(1)" />
          <el-button @click="loadPage(1)">查询</el-button>
          <el-button type="primary" v-if="canEval" @click="openSubmit">发起评估</el-button>
        </div>

        <el-table :data="rows" v-loading="loading" size="small" border @row-click="openDetail">
          <el-table-column prop="evalNo" label="评估单号" width="150" />
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="batchNo" label="批次" width="140" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'CLOSED' ? 'success'
                : row.status === 'PENDING_APPR' ? 'warning' : 'danger'" size="small">
                {{ { PENDING_EVAL: '待判定', PENDING_APPR: '审批中', CLOSED: '已关闭' }[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="判定" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.conclusion" :type="conclusionTag(row.conclusion)" size="small">
                {{ conclusionText(row.conclusion) }}
              </el-tag>
              <span v-else class="dim">待定</span>
            </template>
          </el-table-column>
          <el-table-column label="关联" width="150">
            <template #default="{ row }">
              <span v-if="row.scrapDocNo" class="mono">报废 {{ row.scrapDocNo }}</span>
              <span v-else-if="row.freezeNo" class="mono">冻结 {{ row.freezeNo }}</span>
              <span v-else-if="row.releaseUntil" class="mono">放行至 {{ row.releaseUntil }}</span>
              <span v-else class="dim">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="evalBy" label="发起人" width="110" />
          <el-table-column prop="createDate" label="发起时间" width="155">
            <template #default="{ row }">{{ fmtTime(row.createDate) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click.stop="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="total" :page-size="filters.size"
          :current-page="filters.current" @current-change="p => { filters.current = p; loadPage() }" />
      </el-tab-pane>

      <!-- 待签（页内签署复用底座） -->
      <el-tab-pane label="待我签署" name="todo">
        <div class="toolbar">
          <el-button @click="loadTodo">刷新</el-button>
          <span class="tip">仅显示 ExpiryEval 待办（角色匹配由底座强制，同人不可签）</span>
        </div>
        <el-table :data="todos" v-loading="loading" size="small" border>
          <el-table-column prop="apprNo" label="审批单号" width="150" />
          <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
          <el-table-column prop="nodeName" label="节点" width="140" />
          <el-table-column prop="applyBy" label="发起人" width="110" />
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <el-button link type="success" size="small" @click="openPass(row)">通过</el-button>
              <el-button link type="danger" size="small" @click="openReject(row)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && todos.length === 0" description="暂无待签" />
      </el-tab-pane>
    </el-tabs>

    <!-- 发起评估对话框 -->
    <el-dialog v-model="submitVisible" title="发起效期质量评估（仅锁定批次）" width="520px">
      <el-form label-width="90px">
        <el-form-item label="物料" required>
          <el-input v-model="submitForm.itemCode" placeholder="物料编码" />
        </el-form-item>
        <el-form-item label="批次" required>
          <el-input v-model="submitForm.batchNo" placeholder="批次号（须为锁定中批次）" />
        </el-form-item>
        <el-form-item label="评估说明" required>
          <el-input v-model="submitForm.evalNote" type="textarea" :rows="3" maxlength="1000"
            placeholder="评估说明（必填，如：剩余 25 天拟放行至月底促销消化）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 判定三分支对话框 -->
    <el-dialog v-model="conclusionVisible" :title="`判定 - ${current?.evalNo || ''}`" width="560px">
      <el-descriptions v-if="current" :column="2" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="物料">{{ current.itemCode }}</el-descriptions-item>
        <el-descriptions-item label="批次">{{ current.batchNo }}</el-descriptions-item>
        <el-descriptions-item label="评估说明" :span="2">{{ current.evalNote }}</el-descriptions-item>
      </el-descriptions>

      <el-radio-group v-model="conclusionForm.conclusion" style="margin-bottom: 12px">
        <el-radio label="SCRAP">报废</el-radio>
        <el-radio label="RELEASE">让步放行</el-radio>
        <el-radio label="FREEZE">转质量冻结</el-radio>
      </el-radio-group>

      <el-form label-width="110px">
        <el-form-item v-if="conclusionForm.conclusion === 'RELEASE'" label="放行有效期" required>
          <el-date-picker v-model="conclusionForm.releaseUntil" type="date" value-format="YYYY-MM-DD"
            :disabled-date="d => d.getTime() <= Date.now()" placeholder="必须晚于今日" />
        </el-form-item>
        <el-form-item label="判定说明">
          <el-input v-model="conclusionForm.evalNote" type="textarea" :rows="2" maxlength="1000"
            placeholder="补充判定说明（可选，覆盖发起说明）" />
        </el-form-item>
      </el-form>
      <el-alert v-if="conclusionForm.conclusion === 'SCRAP'" type="warning" :closable="false"
        title="将生成报废单（DRAFT）并挂质量经理审批；驳回则自动作废" />
      <el-alert v-else-if="conclusionForm.conclusion === 'RELEASE'" type="warning" :closable="false"
        title="审批通过写入豁免放行（扫描不再回锁），到期自动回归管控" />
      <el-alert v-else type="danger" :closable="false"
        title="调用 4.9 质量冻结链（审批照挂），本评估单即刻关闭" />

      <template #footer>
        <el-button @click="conclusionVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doConclusion">提交判定</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" :title="`评估详情 ${current?.evalNo || ''}`" size="520px">
      <template v-if="current">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="单号">{{ current.evalNo }}</el-descriptions-item>
          <el-descriptions-item label="物料/批次">{{ current.itemCode }} / {{ current.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="锁定来源">{{ current.lockSource }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusText(current.status) }}</el-descriptions-item>
          <el-descriptions-item label="判定">
            {{ current.conclusion ? conclusionText(current.conclusion) : '待定' }}
          </el-descriptions-item>
          <el-descriptions-item label="评估说明">{{ current.evalNote }}</el-descriptions-item>
          <el-descriptions-item v-if="current.releaseUntil" label="放行有效期">
            {{ current.releaseUntil }}
          </el-descriptions-item>
          <el-descriptions-item v-if="current.scrapDocNo" label="报废单">
            {{ current.scrapDocNo }}
          </el-descriptions-item>
          <el-descriptions-item v-if="current.freezeNo" label="冻结单">
            {{ current.freezeNo }}
          </el-descriptions-item>
          <el-descriptions-item v-if="current.apprOpinion" label="驳回意见">
            {{ current.apprOpinion }}
          </el-descriptions-item>
          <el-descriptions-item label="发起人">{{ current.evalBy }}</el-descriptions-item>
        </el-descriptions>
        <el-button v-if="current.status === 'PENDING_EVAL' && canEval" type="primary"
          style="margin-top: 14px; width: 100%" @click="conclusionVisible = true">
          提交判定
        </el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-drawer>

    <!-- 通过/驳回 -->
    <el-dialog v-model="passVisible" title="评估审批 - 通过" width="460px">
      <el-input v-model="passOpinion" type="textarea" :rows="3" placeholder="意见（可空，默认同意）" />
      <template #footer>
        <el-button @click="passVisible = false">取消</el-button>
        <el-button type="success" :loading="acting" @click="doPass">确认通过</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="rejectVisible" title="评估审批 - 驳回" width="460px">
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="500"
        placeholder="驳回原因（必填，至少 2 字；回待判定可重新提交）" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="acting" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  submitExpiryEvalApi, submitEvalConclusionApi, getExpiryEvalPageApi, getExpiryEvalDetailApi
} from '@/api/inv/expiry'
import {
  getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi
} from '@/api/qms/approval'
import { useUserStore } from '@/store/user'

// 4.10.3 质量评估：发起（锁定批次）→ 判定三分支 → 页内签署 → 详情
const route = useRoute()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const canEval = computed(() => roles.value.includes('ROLE_ADMIN')
  || roles.value.includes('ROLE_QUALITY_ENG') || roles.value.includes('ROLE_QUALITY_MGR'))

const tab = ref('list')
const loading = ref(false)
const acting = ref(false)
const rows = ref([])
const total = ref(0)
const todos = ref([])
const filters = reactive({ status: '', keyword: '', current: 1, size: 20 })

const current = ref(null)
const submitVisible = ref(false)
const conclusionVisible = ref(false)
const detailVisible = ref(false)
const passVisible = ref(false)
const rejectVisible = ref(false)
const submitForm = reactive({ itemCode: '', batchNo: '', evalNote: '' })
const conclusionForm = reactive({ conclusion: 'RELEASE', releaseUntil: '', evalNote: '' })
const passOpinion = ref('')
const rejectReason = ref('')

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}
const statusText = s => ({ PENDING_EVAL: '待判定', PENDING_APPR: '审批中', CLOSED: '已关闭' }[s] || s)
const conclusionText = c => ({ SCRAP: '报废', RELEASE: '让步放行', FREEZE: '转冻结' }[c] || c)
const conclusionTag = c => ({ SCRAP: 'danger', RELEASE: 'success', FREEZE: 'warning' }[c] || 'info')

async function loadPage(page) {
  if (page) filters.current = page
  loading.value = true
  try {
    const res = await getExpiryEvalPageApi({
      status: filters.status || undefined,
      keyword: filters.keyword || undefined,
      current: filters.current, size: filters.size
    })
    rows.value = res.data?.records || []
    total.value = Number(res.data?.total || 0)
  } catch (e) {
    console.warn('[expiry-eval] page failed', e)
  } finally {
    loading.value = false
  }
}

async function loadTodo() {
  loading.value = true
  try {
    const res = await getQmsApprovalTodoApi()
    const all = res.data || []
    todos.value = all.filter(t => t.bizType === 'ExpiryEval' && t.kind !== 'ESCALATE')
  } catch (e) {
    console.warn('[expiry-eval] todo failed', e)
  } finally {
    loading.value = false
  }
}

function openSubmit() {
  // 从 4.10.2 跳转带参
  submitForm.itemCode = route.query.itemCode || ''
  submitForm.batchNo = route.query.batchNo || ''
  submitForm.evalNote = ''
  submitVisible.value = true
}

async function doSubmit() {
  if (!submitForm.itemCode.trim() || !submitForm.batchNo.trim()) {
    ElMessage.warning('物料与批次必填')
    return
  }
  if (!submitForm.evalNote.trim()) {
    ElMessage.warning('评估说明必填')
    return
  }
  acting.value = true
  try {
    const res = await submitExpiryEvalApi({
      itemCode: submitForm.itemCode.trim(),
      batchNo: submitForm.batchNo.trim(),
      evalNote: submitForm.evalNote.trim()
    })
    ElMessage.success(`评估单 ${res.data?.evalNo} 已发起`)
    submitVisible.value = false
    loadPage(1)
  } catch (e) {
    console.warn('[expiry-eval] submit failed', e)
  } finally {
    acting.value = false
  }
}

function openDetail(row) {
  current.value = { ...row }
  detailVisible.value = true
}

async function doConclusion() {
  const c = conclusionForm.conclusion
  if (c === 'RELEASE' && !conclusionForm.releaseUntil) {
    ElMessage.warning('让步放行必填放行有效期（须晚于今日）')
    return
  }
  acting.value = true
  try {
    const res = await submitEvalConclusionApi(current.value.id, {
      conclusion: c,
      releaseUntil: c === 'RELEASE' ? conclusionForm.releaseUntil : undefined,
      evalNote: conclusionForm.evalNote || undefined
    })
    ElMessage.success(`判定已提交：${res.data?.status === 'CLOSED' ? '已关闭' : '待审批'}`)
    conclusionVisible.value = false
    detailVisible.value = false
    loadPage(1)
  } catch (e) {
    console.warn('[expiry-eval] conclusion failed', e)
  } finally {
    acting.value = false
  }
}

function openPass(row) {
  current.value = row
  passOpinion.value = ''
  passVisible.value = true
}

async function doPass() {
  acting.value = true
  try {
    await passQmsApprovalApi(current.value.taskId, passOpinion.value)
    ElMessage.success('已通过')
    passVisible.value = false
    loadTodo()
    loadPage(1)
  } catch (e) {
    console.warn('[expiry-eval] pass failed', e)
  } finally {
    acting.value = false
  }
}

function openReject(row) {
  current.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function doReject() {
  if (!rejectReason.value || rejectReason.value.trim().length < 2) {
    ElMessage.warning('驳回原因至少 2 字')
    return
  }
  acting.value = true
  try {
    await rejectQmsApprovalApi(current.value.taskId, rejectReason.value.trim())
    ElMessage.success('已驳回（回待判定，可重新提交）')
    rejectVisible.value = false
    loadTodo()
    loadPage(1)
  } catch (e) {
    console.warn('[expiry-eval] reject failed', e)
  } finally {
    acting.value = false
  }
}

onMounted(() => {
  loadPage(1)
  loadTodo()
  // 4.10.2 跳转带参 → 直接打开发起对话框
  if (route.query.itemCode && route.query.batchNo) {
    openSubmit()
  }
})
</script>

<style scoped>
.page { padding: 4px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
.dim { color: #909399; font-size: 12px; }
.mono { font-family: monospace; font-size: 12px; }
</style>
