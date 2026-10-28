<template>
  <div class="page-wrap">
    <!-- 冻结状态（spec：存在未关闭 EXCEPTION 单即冻结中，付款 422 阻断回显单号差额） -->
    <el-alert v-if="frozenRows.length" type="error" :closable="false" show-icon style="margin-bottom: 12px;">
      <template #title>
        付款冻结中（C-4.2-15 L2）：{{ frozenRows.length }} 张差异对账单待双签 —
        <span v-for="r in frozenRows" :key="r.id" class="freeze-item">
          {{ r.stmtNo }}（差额 {{ r.diffAmount }}）
        </span>
      </template>
      <div class="hint">冻结期间付款/预付款申请提交将被 422 硬阻断并回显单号与差额；阻断在 2.7.3 付款域执行，双签关闭后自动恢复。</div>
    </el-alert>
    <el-alert v-else type="success" :closable="false" show-icon style="margin-bottom: 12px;"
      title="当前无待双签差异对账单，付款未被对账冻结" />

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>12.6.2 差异处理（差异清单 → 双签解冻，L2 强制审批）</span>
          <span>
            <el-button size="small" type="warning" plain @click="$router.push('/m/12.6.1')">对账匹配 12.6.1</el-button>
            <el-button size="small" @click="reload">刷新</el-button>
          </span>
        </div>
      </template>

      <!-- 待处理差异（EXCEPTION 只读清单 + 双签动作） -->
      <div class="sub-title">待处理差异对账单（{{ rows.length }}）</div>
      <el-table :data="rows" size="small" border v-loading="loading" style="margin-top: 8px;">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-box" v-loading="row._loading">
              <div v-if="row._match">
                <el-descriptions :column="4" border size="small" style="margin-bottom: 8px;">
                  <el-descriptions-item label="ERP 侧合计">{{ row._match.erpTotal }}</el-descriptions-item>
                  <el-descriptions-item label="供应商侧合计">{{ row._match.supplierTotal }}</el-descriptions-item>
                  <el-descriptions-item label="差异">{{ row._match.diffAmount }}</el-descriptions-item>
                  <el-descriptions-item label="差异率">{{ pct(row._match.diffRate) }}</el-descriptions-item>
                </el-descriptions>
                <el-table :data="redOf(row._match)" size="mini" border max-height="160">
                  <el-table-column prop="flag" label="类型" width="130">
                    <template #default="{ row: d }">
                      <el-tag type="danger" size="mini">{{ d.flag === 'ERP_ONLY' ? '仅 ERP 侧' : '仅供应商侧' }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="说明" min-width="150">
                    <template #default="{ row: d }">{{ d.desc || d.accrualNo || '—' }}</template>
                  </el-table-column>
                  <el-table-column label="金额" width="100">
                    <template #default="{ row: d }">{{ d.amount != null ? d.amount : d.openAmount }}</template>
                  </el-table-column>
                  <el-table-column prop="postingDocNo" label="凭证号" width="140">
                    <template #default="{ row: d }">{{ d.postingDocNo || '—' }}</template>
                  </el-table-column>
                </el-table>
                <div class="hint mt8">匹配留痕：{{ row._match.matchBy }} @ {{ row._match.matchAt }}（只读，重匹配去 12.6.1）</div>
              </div>
              <el-empty v-else description="尚未执行系统匹配（去 12.6.1 执行）" :image-size="60" />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="stmtNo" label="对账单号" width="150">
          <template #default="{ row }"><b>{{ row.stmtNo }}</b></template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
        <el-table-column prop="stmtAmount" label="对账金额" width="100" align="right" />
        <el-table-column prop="baseAmount" label="暂估基准" width="100" align="right" />
        <el-table-column label="差额" width="100" align="right">
          <template #default="{ row }">
            <span style="color: #F56C6C;">{{ row.diffAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="差异率" width="90" align="right">
          <template #default="{ row }">{{ pct(row.diffRate) }}</template>
        </el-table-column>
        <el-table-column label="双签状态" width="200">
          <template #default="{ row }">
            <el-tag size="mini" :type="row.pmConfirmBy ? 'warning' : 'info'">
              采购员 {{ row.pmConfirmBy ? '已签' : '未签' }}
            </el-tag>
            <el-tag size="mini" :type="row.finConfirmBy ? 'success' : 'info'" style="margin-left: 4px;">
              财务 {{ row.finConfirmBy ? '已签' : '未签' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="warning" size="small" @click="initiate(row)">发起双签</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />

      <!-- 我的待签节点（StatementReconcile） -->
      <div class="sub-title" style="margin-top: 16px;">我的双签待办（{{ myTasks.length }}）</div>
      <el-table :data="myTasks" size="small" border style="margin-top: 8px;" v-if="myTasks.length">
        <el-table-column prop="title" label="审批标题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="nodeName" label="节点" width="120" />
        <el-table-column prop="roleRequired" label="签署角色" width="150" />
        <el-table-column prop="apprNo" label="审批单号" width="150" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openSign(row)">签署</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="hint mt8">当前角色无待签双签节点（发起后由采购员与财务先后签署）</div>

      <!-- 已关闭（双签完成留痕） -->
      <div class="sub-title" style="margin-top: 16px;">已关闭对账单（双签留痕）</div>
      <el-table :data="closedRows" size="small" border style="margin-top: 8px;" max-height="220">
        <el-table-column prop="stmtNo" label="对账单号" width="150" />
        <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
        <el-table-column prop="diffAmount" label="差额" width="100" align="right" />
        <el-table-column label="采购员签署" min-width="180">
          <template #default="{ row }">{{ row.pmConfirmBy }}（{{ row.pmOpinion }}）</template>
        </el-table-column>
        <el-table-column label="财务签署" min-width="180">
          <template #default="{ row }">{{ row.finConfirmBy }}（{{ row.finOpinion }}）</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 签署对话框（意见必填，spec L5） -->
    <el-dialog v-model="signDlg" title="双签确认" width="460px">
      <el-form label-width="90px">
        <el-form-item label="审批标题"><span>{{ currentTask.title }}</span></el-form-item>
        <el-form-item label="节点"><span>{{ currentTask.nodeName }}（{{ currentTask.roleRequired }}）</span></el-form-item>
        <el-form-item label="确认意见" required>
          <el-input v-model="signOpinion" type="textarea" :rows="3"
            placeholder="必填：确认差异处理意见（如「接受差异，同意解冻」）" />
        </el-form-item>
      </el-form>
      <div class="hint">签署后并行双签两节点齐即解冻（CLOSED）；意见缺失后端 422 拒绝。</div>
      <template #footer>
        <el-button @click="signDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitSign">确认签署</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getStatementPageApi, getStatementDetailApi, confirmStatementApi,
  getQmsApprovalTodoApi, passQmsApprovalApi
} from '@/api/proc/reconciliation-console'

const loading = ref(false)
const submitting = ref(false)
const rows = ref([])
const closedRows = ref([])
const total = ref(0)
const myTasks = ref([])
const filters = reactive({ current: 1, size: 10 })

const frozenRows = computed(() => rows.value)

function pct(v) {
  if (v == null) return '—'
  return (Number(v) * 100).toFixed(2) + '%'
}
function redOf(m) {
  return [...(m.supplierOnly || []), ...(m.erpOnly || [])]
}

async function load() {
  loading.value = true
  try {
    const { data } = await getStatementPageApi({
      current: filters.current, size: filters.size, status: 'EXCEPTION'
    })
    const recs = (data && data.records) || []
    rows.value = recs
    total.value = Number((data && data.total) || 0)
    // 展开区差异清单（只读）：懒加载匹配结果
    for (const r of recs) {
      if (!r._match && !r._tried) {
        r._tried = true
        getStatementDetailApi(r.id).then(({ data: d }) => {
          if (d && d.matchResult) {
            try { r._match = JSON.parse(d.matchResult) } catch { r._match = null }
          }
        }).catch(() => {})
      }
    }
    const closed = await getStatementPageApi({ current: 1, size: 20, status: 'CLOSED' })
    closedRows.value = ((closed.data && closed.data.records) || [])
  } finally { loading.value = false }
}

async function loadTodo() {
  try {
    const { data } = await getQmsApprovalTodoApi()
    myTasks.value = (data || []).filter(t => t.bizType === 'StatementReconcile')
  } catch { myTasks.value = [] }
}

async function initiate(row) {
  try {
    await ElMessageBox.confirm(
      `对账单 ${row.stmtNo} 差额 ${row.diffAmount}（差异率 ${pct(row.diffRate)}），` +
      '发起后进入双签（采购员 → 财务），两签齐即解冻付款。是否发起？',
      '发起双签', { type: 'warning' })
  } catch (e) { return }
  try {
    await confirmStatementApi(row.id)
    ElMessage.success('已发起双签（待节点签署），冻结解除前付款申请仍会被 422 阻断')
    await load()
  } catch (e) { /* 拦截器已提示（重复发起 422 等） */ }
}

const signDlg = ref(false)
const currentTask = ref({})
const signOpinion = ref('')

function openSign(task) {
  currentTask.value = task
  signOpinion.value = ''
  signDlg.value = true
}

async function submitSign() {
  if (!signOpinion.value || !signOpinion.value.trim()) {
    ElMessage.warning('确认意见必填（spec L5 留痕）')
    return
  }
  submitting.value = true
  try {
    await passQmsApprovalApi(currentTask.value.taskId, signOpinion.value.trim())
    ElMessage.success('已签署；两节点齐后对账单关闭并解冻')
    signDlg.value = false
    await Promise.all([load(), loadTodo()])
  } finally { submitting.value = false }
}

function reload() { load(); loadTodo() }

onMounted(reload)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.sub-title { font-size: 13px; font-weight: 600; color: #303133; }
.hint { color: #909399; font-size: 12px; }
.mt8 { margin-top: 8px; }
.freeze-item { display: inline-block; margin-right: 8px; font-weight: 600; }
.expand-box { padding: 8px 16px; font-size: 12px; color: #606266; }
</style>
