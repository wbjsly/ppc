<template>
  <div class="cc-page">
    <el-alert type="warning" :closable="false" style="margin-bottom: 12px;"
      title="让步接收（2.5.2 / 6.4.2）：申请要素必填 → 双签（质量经理 + 技术负责人，缺一不生效）→ 过账锁定 → 核销放行"
      description="限制条件（有效期 / 批准数量上限 / 使用范围）过账时写入库存快照；核销逐次校验，越界 422 并留 REJECTED 留痕。安全/法规 CTQ 不合格禁止让步（BR-4.12-26）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 160px;" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="双签中" value="PENDING_APPROVE" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="已作废" value="CANCELLED" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="单号 / NCR / 物料" clearable
          style="width: 220px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canApply" type="success" @click="openCreate">新建申请</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
        <el-table-column prop="concessionNo" label="让步单号" width="160">
          <template #default="{ row }"><b>{{ row.concessionNo }}</b></template>
        </el-table-column>
        <el-table-column prop="ncrNo" label="NCR" width="150" />
        <el-table-column prop="itemCode" label="物料" width="130" />
        <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column prop="batchNo" label="批次" width="110" />
        <el-table-column label="批量/上限" width="110" align="right">
          <template #default="{ row }">{{ row.qty }} / {{ row.limitQty }}</template>
        </el-table-column>
        <el-table-column prop="limitUntil" label="有效期至" width="110" />
        <el-table-column prop="limitScope" label="使用范围" min-width="120" show-overflow-tooltip />
        <el-table-column label="已用/剩余" width="110" align="right">
          <template #default="{ row }">{{ row.usedQty }} / {{ row.limitQty - row.usedQty }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{
              DRAFT: 'info', PENDING_APPROVE: 'warning', APPROVED: 'success',
              REJECTED: 'danger', CANCELLED: 'info'
            }[row.status]">
              {{ { DRAFT: '草稿', PENDING_APPROVE: '双签中', APPROVED: '已批准',
                   REJECTED: '已驳回', CANCELLED: '已作废' }[row.status] || row.status }}
            </el-tag>
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

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer"
      :title="detail.concession ? `${detail.concession.concessionNo} · ${STATUS_TEXT[detail.concession.status]}` : '让步接收详情'"
      size="720px" destroy-on-close>
      <div v-if="detail.concession">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="NCR">{{ detail.concession.ncrNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="{
              DRAFT: 'info', PENDING_APPROVE: 'warning', APPROVED: 'success',
              REJECTED: 'danger', CANCELLED: 'info'
            }[detail.concession.status]">
              {{ STATUS_TEXT[detail.concession.status] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="物料">{{ detail.concession.itemCode }} {{ detail.concession.itemName }}</el-descriptions-item>
          <el-descriptions-item label="批次">{{ detail.concession.batchNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="批量 / 批准上限">
            {{ detail.concession.qty }} / {{ detail.concession.limitQty }}
          </el-descriptions-item>
          <el-descriptions-item label="已用 / 剩余">
            <b :style="{ color: Number(detail.remainQty) === 0 ? '#F56C6C' : '' }">
              {{ detail.usedQty }} / {{ detail.remainQty }}
            </b>
          </el-descriptions-item>
          <el-descriptions-item label="有效期至">{{ detail.concession.limitUntil }}</el-descriptions-item>
          <el-descriptions-item label="使用范围">{{ detail.concession.limitScope }}</el-descriptions-item>
          <el-descriptions-item label="让步原因" :span="2">{{ detail.concession.reason }}</el-descriptions-item>
          <el-descriptions-item label="技术评估" :span="2">{{ detail.concession.techAssess }}</el-descriptions-item>
          <el-descriptions-item label="风险评估" :span="2">{{ detail.concession.riskAssess }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.concession.rejectReason" label="驳回原因" :span="2">
            <span style="color:#F56C6C;">{{ detail.concession.rejectReason }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 双签进度 -->
        <h4 class="sec">双签进度（质量经理 + 技术负责人，缺一不生效）</h4>
        <el-steps :active="signActive" align-center finish-status="success" size="small">
          <el-step title="提交申请" :description="detail.concession.submittedDate || '—'" />
          <el-step title="质量经理（第 1 签）" :description="signDesc('ROLE_QUALITY_MGR')" />
          <el-step title="技术负责人（第 2 签）" :description="signDesc('ROLE_TECH_OWNER')" />
          <el-step title="生效" :description="detail.concession.status === 'APPROVED' ? '已批准' : '—'" />
        </el-steps>
        <div v-if="detail.concession.status === 'DRAFT'" style="margin-top:8px;">
          <el-button v-if="canApply" type="primary" size="small" @click="doSubmit">提交双签</el-button>
          <el-button v-if="canApply" size="small" @click="doCancel">作废</el-button>
        </div>

        <!-- 核销放行 -->
        <h4 class="sec">核销放行（逐次校验有效期 / 累计量 / 使用范围）</h4>
        <div v-if="detail.concession.status === 'APPROVED'" class="op-block">
          <el-input v-model="woForm.scope" placeholder="使用范围（须含限制条件关键字，如：总装车间A线领用）"
            style="margin-bottom:8px;" />
          <div style="display:flex;gap:8px;">
            <el-input-number v-model="woForm.qty" :min="0.0001" :precision="4" placeholder="数量" />
            <el-input v-model="woForm.refDocNo" placeholder="关联出库单号（可选）" style="width:180px;" />
            <el-button type="primary" size="default" @click="doWriteOff">核销</el-button>
          </div>
          <div style="font-size:12px;color:#909399;margin-top:6px;">
            剩余额度 {{ detail.remainQty }} · 有效期至 {{ detail.concession.limitUntil }}
          </div>
        </div>

        <el-table :data="detail.writeoffs || []" size="mini" border style="margin-top:10px;" max-height="240">
          <el-table-column prop="writeDate" label="时间" width="155" />
          <el-table-column prop="qty" label="数量" width="80" align="right" />
          <el-table-column prop="scope" label="使用范围" min-width="140" show-overflow-tooltip />
          <el-table-column prop="remainQty" label="剩余" width="80" align="right" />
          <el-table-column prop="operator" label="操作人" width="100" />
          <el-table-column label="结果" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'OK' ? 'success' : 'danger'">
                {{ row.status === 'OK' ? '核销' : '被拒' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="rejectReason" label="拒绝原因" min-width="160" show-overflow-tooltip />
        </el-table>
        <el-empty v-if="!(detail.writeoffs || []).length" description="暂无核销记录" :image-size="60" />
      </div>
    </el-drawer>

    <!-- 新建申请 -->
    <el-dialog v-model="createVisible" title="新建让步接收申请" width="560px">
      <el-form label-width="110px" size="small">
        <el-form-item label="NCR ID" required>
          <el-input v-model="createForm.ncrId" placeholder="不合格品 NCR 的 ID（处置须已评审为让步接收）" />
        </el-form-item>
        <el-form-item label="让步原因" required>
          <el-input v-model="createForm.reason" type="textarea" :rows="2" placeholder="BR-4.2-26 必填" />
        </el-form-item>
        <el-form-item label="技术评估" required>
          <el-input v-model="createForm.techAssess" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="风险评估" required>
          <el-input v-model="createForm.riskAssess" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="有效期至" required>
          <el-date-picker v-model="createForm.limitUntil" type="date" value-format="YYYY-MM-DD"
            placeholder="限制有效期（BR-4.12-28）" />
        </el-form-item>
        <el-form-item label="批准数量上限" required>
          <el-input-number v-model="createForm.limitQty" :min="0.0001" :precision="4" />
          <span style="font-size:12px;color:#909399;margin-left:8px;">不得超过本批不合格量</span>
        </el-form-item>
        <el-form-item label="使用范围" required>
          <el-input v-model="createForm.limitScope" placeholder="如：总装车间A线（核销出库用途须包含此关键字）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getConcessionPageApi, getConcessionDetailApi, createConcessionApi,
  submitConcessionApi, cancelConcessionApi, writeOffApi
} from '@/api/qms/concession'

const userStore = useUserStore()

const STATUS_TEXT = {
  DRAFT: '草稿', PENDING_APPROVE: '双签中', APPROVED: '已批准',
  REJECTED: '已驳回', CANCELLED: '已作废'
}
const ROLE_TEXT = { ROLE_QUALITY_MGR: '质量经理', ROLE_TECH_OWNER: '技术负责人' }

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const filters = reactive({ status: '', keyword: '', current: 1, size: 10 })

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const hasRole = (...want) => myRoles.value.some(r => r === 'ROLE_ADMIN' || want.includes(r))
const canApply = computed(() => hasRole('ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR'))

async function load() {
  loading.value = true
  try {
    const res = await getConcessionPageApi({
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
const woForm = reactive({ qty: undefined, scope: '', refDocNo: '' })

const signActive = computed(() => {
  const st = detail.value.concession && detail.value.concession.status
  if (st === 'APPROVED') return 4
  const logs = detail.value.approval && detail.value.approval.tasks
  if (!logs) return st === 'PENDING_APPROVE' ? 1 : 0
  const passed = logs.filter(t => t.status === 'PASSED').length
  return Math.min(1 + passed, 3)
})
function signDesc(role) {
  const tasks = (detail.value.approval && detail.value.approval.tasks) || []
  const t = tasks.find(x => x.roleRequired === role)
  if (!t) return '—'
  if (t.status === 'PASSED') return `${t.signerName || t.signer || ''} 已签`
  if (t.status === 'REJECTED') return '已驳回'
  return '待签署'
}

async function openDetail(row) {
  const res = await getConcessionDetailApi(row.id)
  detail.value = res.data || {}
  woForm.qty = undefined
  woForm.scope = ''
  woForm.refDocNo = ''
  drawer.value = true
}

async function doSubmit() {
  try {
    await submitConcessionApi(detail.value.concession.id)
    ElMessage.success('已提交双签（质量经理 + 技术负责人）')
    openDetail({ id: detail.value.concession.id })
    load()
  } catch { /* 拦截器已弹错 */ }
}

async function doCancel() {
  try {
    const { value } = await ElMessageBox.prompt('作废原因（至少 2 字；记录永久留痕）', '作废申请',
      { inputPattern: /.{2,}/, inputErrorMessage: '至少 2 字' })
    await cancelConcessionApi(detail.value.concession.id, value)
    ElMessage.success('已作废')
    openDetail({ id: detail.value.concession.id })
    load()
  } catch { /* 取消或拦截器已处理 */ }
}

async function doWriteOff() {
  try {
    await writeOffApi(detail.value.concession.id, { ...woForm })
    ElMessage.success('核销成功')
    openDetail({ id: detail.value.concession.id })
    load()
  } catch { /* 拦截器已弹错（含越界 422） */ }
}

// ---------- 新建 ----------
const createVisible = ref(false)
const createForm = reactive({
  ncrId: '', reason: '', techAssess: '', riskAssess: '',
  limitUntil: '', limitQty: undefined, limitScope: ''
})
function openCreate() {
  createForm.ncrId = ''
  createForm.reason = ''
  createForm.techAssess = ''
  createForm.riskAssess = ''
  createForm.limitUntil = ''
  createForm.limitQty = undefined
  createForm.limitScope = ''
  createVisible.value = true
}
async function doCreate() {
  try {
    const res = await createConcessionApi({ ...createForm })
    ElMessage.success('草稿已创建，请提交双签')
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
.cc-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sec { margin: 16px 0 8px; font-size: 14px; color: #303133; }
.op-block { background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px; padding: 12px; }
</style>
