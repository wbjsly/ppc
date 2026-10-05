<template>
  <div class="scar-page">
    <el-alert type="warning" :closable="false" style="margin-bottom: 12px;"
      title="SCAR 供应商质量索赔（6.8）：NCR 退货/挑选自动带出 → 重大（≥1万）质量经理审批发出 → 5 工作日回复"
      description="SENT 起该供方抽样强制加严一档（BR-4.12-44）；回复超期升级采购经理 + 响应及时性扣分 + 每 3 天提醒；SQE 代录 8D 回复（浅层根因退回计数）；扣款单待财务确认 → 待抵扣（应付桩 D3）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 140px;" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发出" value="SENT" />
          <el-option label="待验证" value="VERIFYING" />
          <el-option label="已关闭" value="CLOSED" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="SCAR 号 / 供应商 / NCR" clearable
          style="width: 220px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canEdit" type="success" @click="openCreate">发起 SCAR</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
        <el-table-column prop="scarNo" label="SCAR 号" width="150">
          <template #default="{ row }"><b>{{ row.scarNo }}</b></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="supplierName" label="供应商" width="130" show-overflow-tooltip />
        <el-table-column label="触发" width="110" align="center">
          <template #default="{ row }">
            {{ { NCR_RETURN: '退货/挑选', REPEAT: '重复不合格', STOPPAGE: '停线投诉' }[row.triggerType] || row.triggerType }}
          </template>
        </el-table-column>
        <el-table-column prop="claimAmount" label="索赔" width="100" align="right" />
        <el-table-column label="状态" width="95" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ DRAFT: 'info', SENT: 'warning', VERIFYING: 'primary',
              CLOSED: 'success' }[row.status]">
              {{ { DRAFT: '草稿', SENT: '已发出', VERIFYING: '待验证', CLOSED: '已关闭' }[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回复时限" width="140">
          <template #default="{ row }">
            <template v-if="row.replyDueDate">
              <span :style="{ color: row.replyOverdue ? '#F56C6C' : '' }">{{ row.replyDueDate }}</span>
              <el-tag v-if="row.escalatedFlag === '1'" type="danger" size="small" style="margin-left:4px;">
                超期扣分
              </el-tag>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="退回" width="70" align="center">
          <template #default="{ row }">
            <span v-if="row.rejectCount > 0" style="color:#F56C6C;">{{ row.rejectCount }} 次</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>

    <!-- 详情 -->
    <el-drawer v-model="drawer"
      :title="detail.scar ? `${detail.scar.scarNo} · ${STATUS_TEXT[detail.scar.status]}` : 'SCAR 详情'"
      size="700px" destroy-on-close>
      <div v-if="detail.scar">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="标题" :span="2">{{ detail.scar.title }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.scar.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="触发类型">
            {{ { NCR_RETURN: '退货/挑选', REPEAT: '重复不合格', STOPPAGE: '停线投诉' }[detail.scar.triggerType] }}
          </el-descriptions-item>
          <el-descriptions-item label="关联 NCR">{{ detail.scar.ncrNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="索赔金额">¥ {{ detail.scar.claimAmount }}</el-descriptions-item>
          <el-descriptions-item label="回复时限">{{ detail.scar.replyDueDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="超期升级">
            <el-tag v-if="detail.scar.escalatedFlag === '1'" type="danger" size="small">已升采购经理+扣分</el-tag>
            <span v-else>正常</span>
          </el-descriptions-item>
          <el-descriptions-item label="缺陷描述" :span="2">{{ detail.scar.defectDesc || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.scar.claimItems" label="索赔明细" :span="2">
            {{ detail.scar.claimItems }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="op-block" style="margin-top:12px;">
          <template v-if="canEdit && detail.scar.status === 'DRAFT'">
            <el-button type="primary" size="small" @click="doSubmit">发出（重大走质量经理审批）</el-button>
          </template>
          <template v-else-if="canEdit && detail.scar.status === 'SENT'">
            <el-input v-model="replyText" type="textarea" :rows="3"
              placeholder="SQE 代录供应商 8D 回复（须含 5Why，浅层根因将被退回并计数）" />
            <el-button type="primary" size="small" style="margin-top:8px;" @click="doReply">代录回复</el-button>
          </template>
          <template v-else-if="canEdit && detail.scar.status === 'VERIFYING'">
            <el-input v-model="verifyConclusion" type="textarea" :rows="2" placeholder="验证结论（必填）" />
            <el-button type="success" size="small" style="margin-top:8px;" @click="doVerify(true)">验证通过（关闭）</el-button>
            <el-button type="danger" size="small" style="margin-top:8px;margin-left:8px;" @click="doVerify(false)">
              退回（重发时限）
            </el-button>
          </template>
          <el-alert v-else-if="detail.scar.status === 'CLOSED'" type="success" :closable="false"
            :title="`已关闭 · 验证 ${detail.scar.verifyResult || ''}（${detail.scar.verifyBy || ''}）`" />
        </div>

        <!-- 扣款单 -->
        <h4 class="sec">扣款单（{{ (detail.deductions || []).length }}）</h4>
        <el-table :data="detail.deductions || []" size="mini" border>
          <el-table-column prop="deductNo" label="扣款单号" width="150" />
          <el-table-column prop="amount" label="金额" width="100" align="right" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ PENDING_FINANCE: 'warning', CONFIRMED: 'primary',
                TO_DEDUCT: 'success', DISPUTED: 'danger' }[row.status]">
                {{ { PENDING_FINANCE: '待确认', CONFIRMED: '已确认',
                     TO_DEDUCT: '待抵扣', DISPUTED: '争议中' }[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <template v-if="canEdit">
                <el-button v-if="row.status === 'PENDING_FINANCE'" link type="primary"
                  @click="doFinance(row)">财务确认</el-button>
                <el-button v-if="row.status === 'CONFIRMED'" link type="success"
                  @click="doToDeduct(row)">推送抵扣</el-button>
                <el-button v-if="['TO_DEDUCT', 'DISPUTED'].includes(row.status)" link type="danger"
                  @click="doDispute(row)">{{ row.status === 'DISPUTED' ? '解除争议' : '争议' }}</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="canEdit && detail.scar.status !== 'DRAFT'" style="margin-top:8px;">
          <el-button size="small" @click="openDeduction">生成扣款单</el-button>
        </div>
        <el-empty v-else-if="!(detail.deductions || []).length && detail.scar.status === 'DRAFT'"
          description="发出后可生成扣款单" :image-size="60" />
      </div>
    </el-drawer>

    <!-- 发起 -->
    <el-dialog v-model="createVisible" title="发起 SCAR" width="520px">
      <el-form label-width="100px" size="small">
        <el-form-item label="触发类型" required>
          <el-select v-model="createForm.triggerType" style="width:100%;">
            <el-option label="退货/挑选（NCR）" value="NCR_RETURN" />
            <el-option label="3 月 3 次重复不合格" value="REPEAT" />
            <el-option label="停线投诉" value="STOPPAGE" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商" required>
          <el-input v-model="createForm.supplierId" placeholder="供应商 ID" />
        </el-form-item>
        <el-form-item label="供应商名称">
          <el-input v-model="createForm.supplierName" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="createForm.title" />
        </el-form-item>
        <el-form-item label="索赔金额">
          <el-input-number v-model="createForm.claimAmount" :min="0" :precision="2" />
          <span style="font-size:12px;color:#909399;margin-left:8px;">≥1 万为重大，走质量经理审批</span>
        </el-form-item>
        <el-form-item label="缺陷描述">
          <el-input v-model="createForm.defectDesc" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建草稿</el-button>
      </template>
    </el-dialog>

    <!-- 扣款单 -->
    <el-dialog v-model="deductionVisible" title="生成扣款单" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="扣款金额" required>
          <el-input-number v-model="deductionForm.amount" :min="0.01" :precision="2" />
          <div style="font-size:12px;color:#909399;">不超过索赔额 ¥ {{ detail.scar && detail.scar.claimAmount }}</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="deductionForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deductionVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreateDeduction">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getScarPageApi, getScarDetailApi, createScarApi, submitScarApi, replyScarApi,
  verifyScarApi, createDeductionApi, submitDeductionFinanceApi, toDeductApi, disputeApi
} from '@/api/qms/scar'

const userStore = useUserStore()
const STATUS_TEXT = { DRAFT: '草稿', SENT: '已发出', VERIFYING: '待验证', CLOSED: '已关闭' }

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const filters = reactive({ status: '', keyword: '', current: 1, size: 10 })

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canEdit = computed(() => myRoles.value.some(r =>
  ['ROLE_ADMIN', 'ROLE_SQE', 'ROLE_QUALITY_MGR'].includes(r)))

async function load() {
  loading.value = true
  try {
    const res = await getScarPageApi({
      current: filters.current, size: filters.size,
      status: filters.status || undefined, keyword: filters.keyword || undefined
    })
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}

// ---------- 详情 ----------
const drawer = ref(false)
const detail = ref({})
const replyText = ref('')
const verifyConclusion = ref('')

async function openDetail(row) {
  const res = await getScarDetailApi(row.id)
  detail.value = res.data || {}
  replyText.value = ''
  verifyConclusion.value = ''
  drawer.value = true
}
function refresh() {
  openDetail({ id: detail.value.scar.id })
  load()
}

async function doSubmit() {
  try {
    await submitScarApi(detail.value.scar.id)
    ElMessage.success('已发出（重大 SCAR 请在质量审批待办完成审批）')
    refresh()
  } catch { /* 拦截器已弹错 */ }
}
async function doReply() {
  try {
    await replyScarApi(detail.value.scar.id, replyText.value)
    ElMessage.success('回复已录入，待验证')
    refresh()
  } catch { /* 拦截器已弹错（浅层根因退回 422） */ }
}
async function doVerify(pass) {
  try {
    await verifyScarApi(detail.value.scar.id, pass, verifyConclusion.value)
    ElMessage.success(pass ? '验证通过，SCAR 关闭' : '已退回，回复时限重置')
    refresh()
  } catch { /* 拦截器已弹错 */ }
}

// ---------- 扣款 ----------
const deductionVisible = ref(false)
const deductionForm = reactive({ amount: undefined, remark: '' })
function openDeduction() {
  deductionForm.amount = undefined
  deductionForm.remark = ''
  deductionVisible.value = true
}
async function doCreateDeduction() {
  try {
    await createDeductionApi(detail.value.scar.id, { ...deductionForm })
    ElMessage.success('扣款单已创建（待财务确认）')
    deductionVisible.value = false
    refresh()
  } catch { /* 拦截器已弹错 */ }
}
async function doFinance(row) {
  try {
    await submitDeductionFinanceApi(row.id)
    ElMessage.success('已提交财务确认（ADMIN 代签：质量审批待办）')
    refresh()
  } catch { /* 拦截器已弹错 */ }
}
async function doToDeduct(row) {
  try {
    await toDeductApi(row.id)
    ElMessage.success('已推送待抵扣（应付货款抵扣为 2.7 桩）')
    refresh()
  } catch { /* 拦截器已弹错 */ }
}
async function doDispute(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      row.status === 'DISPUTED' ? '争议解除说明' : '争议原因', '扣款争议',
      { inputPattern: /.{2,}/, inputErrorMessage: '至少 2 字' })
    await disputeApi(row.id, value)
    ElMessage.success(row.status === 'DISPUTED' ? '争议已解除' : '已暂挂争议')
    refresh()
  } catch { /* 取消或拦截器已处理 */ }
}

// ---------- 发起 ----------
const createVisible = ref(false)
const createForm = reactive({
  triggerType: 'NCR_RETURN', supplierId: '', supplierName: '', title: '',
  claimAmount: undefined, defectDesc: ''
})
function openCreate() {
  createForm.triggerType = 'NCR_RETURN'
  createForm.supplierId = ''
  createForm.supplierName = ''
  createForm.title = ''
  createForm.claimAmount = undefined
  createForm.defectDesc = ''
  createVisible.value = true
}
async function doCreate() {
  try {
    const res = await createScarApi({ ...createForm })
    ElMessage.success('SCAR 草稿已创建')
    createVisible.value = false
    load()
    if (res.data) openDetail(res.data)
  } catch { /* 拦截器已弹错 */ }
}

onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.getUserInfo() } catch { /* ignore */ }
  }
  load()
})
</script>

<style scoped>
.scar-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sec { margin: 16px 0 8px; font-size: 14px; color: #303133; }
.op-block { background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px; padding: 12px; }
</style>
