<template>
  <div class="page-wrap">
    <!-- 列表 -->
    <el-card shadow="never" class="mb12">
      <div class="card-head mb12">
        <h4 style="margin:0">伙伴引入申请（12.1.1 伙伴注册）</h4>
        <div>
          <el-select v-model="query.status" placeholder="状态" clearable style="width:140px"
                     size="small" @change="load">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="审批中" value="APPROVING" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
          <el-input v-model="query.keyword" placeholder="企业名/编号/信用代码" clearable
                    style="width:200px;margin-left:8px" size="small" @keyup.enter="load" />
          <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
          <el-button size="small" type="primary" style="margin-left:8px"
                     @click="openCreate">新建申请</el-button>
        </div>
      </div>
      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="applyNo" label="申请编号" width="130" />
        <el-table-column prop="supplierName" label="企业名称" min-width="160" />
        <el-table-column prop="taxNo" label="统一社会信用代码" width="180" />
        <el-table-column label="风险等级" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.riskLevel === 'HIGH' ? 'danger' : 'info'">
              {{ row.riskLevel === 'HIGH' ? '高(三级)' : '普通(两级)' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rejectCount" label="驳回" width="60" align="center" />
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 'DRAFT'" size="small" text type="primary"
                       @click="doSubmit(row)">提交</el-button>
            <el-button v-if="row.status === 'APPROVING'" size="small" text type="warning"
                       @click="doWithdraw(row)">撤回</el-button>
            <el-button v-if="row.status === 'APPROVED' && row.accountId" size="small" text
                       type="success" @click="openLink(row)">激活链接</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="mt12" style="justify-content:flex-end"
                     :current-page="query.current" :page-size="query.size"
                     :total="total" layout="total, prev, pager, next"
                     @current-change="onPage" />
    </el-card>

    <!-- 新建/编辑申请（仅 DRAFT） -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑申请（草稿）' : '新建引入申请'"
               width="640px">
      <el-form :model="form" label-width="130px" size="small">
        <el-form-item label="企业名称" required>
          <el-input v-model="form.supplierName" placeholder="全称（与营业执照一致）" />
        </el-form-item>
        <el-form-item label="统一社会信用代码" required>
          <el-input v-model="form.taxNo" maxlength="18" show-word-limit
                    placeholder="18 位（提交时硬校验 L1）" />
        </el-form-item>
        <el-form-item label="联系人" required>
          <el-input v-model="form.contactName" />
        </el-form-item>
        <el-form-item label="联系方式" required>
          <el-input v-model="form.contactPhone" />
        </el-form-item>
        <el-form-item label="联系邮箱" required>
          <el-input v-model="form.contactEmail" placeholder="激活链接送达邮箱" />
        </el-form-item>
        <el-form-item label="主营品类">
          <el-input v-model="form.mainCategory" placeholder="物料组编码，逗号分隔（含白名单组→强制三级）" />
        </el-form-item>
        <el-form-item label="预估年采购额">
          <el-input-number v-model="form.estAnnualAmount" :min="0" :precision="2"
                           placeholder=">100 万强制三级审核" />
          <span class="hint">&gt;100 万 → 高风险三级审核</span>
        </el-form-item>
        <el-form-item label="开户行" required>
          <el-input v-model="form.bankName" />
        </el-form-item>
        <el-form-item label="付款条件" required>
          <el-input v-model="form.paymentTerms" placeholder="如 NET30" />
        </el-form-item>

        <!-- 证照子表 -->
        <el-divider content-position="left">资质证照（每条须上传扫描件）</el-divider>
        <div v-for="(c, i) in form.certs" :key="i" class="cert-row">
          <el-select v-model="c.certType" placeholder="类型" style="width:150px" size="small">
            <el-option label="营业执照" value="LICENSE" />
            <el-option label="质量体系认证" value="QMS" />
            <el-option label="环保资质" value="ENV" />
            <el-option label="行业许可证" value="PERMIT" />
            <el-option label="其它" value="OTHER" />
          </el-select>
          <el-input v-model="c.certNo" placeholder="证照编号" size="small"
                    style="width:170px;margin-left:6px" />
          <el-date-picker v-model="c.expireDate" type="date" placeholder="有效期"
                          value-format="YYYY-MM-DD" size="small"
                          style="width:140px;margin-left:6px" />
          <el-upload :action="uploadUrl" :headers="uploadHeaders" :show-file-list="false"
                     :data="{ bizType: 'PARTNER_APPLY_CERT', bizId: c.id || '' }"
                     :on-success="(r) => onUploaded(r, c)" :on-error="onUploadError"
                     :disabled="!c.id" style="display:inline-block;margin-left:6px">
            <el-button size="small" :disabled="!c.id">
              {{ c.attachCount > 0 ? `已传(${c.attachCount})` : '上传附件' }}
            </el-button>
          </el-upload>
          <el-button size="small" text type="danger" @click="form.certs.splice(i, 1)">删</el-button>
          <div v-if="!c.id" class="hint">先保存证照行（填写保存按钮）后可上传附件</div>
        </div>
        <div>
          <el-button size="small" @click="addCert">+ 加证照</el-button>
          <el-button size="small" @click="saveCertRows">保存证照行</el-button>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="saveApply">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 激活链接 -->
    <el-dialog v-model="linkVisible" title="门户激活链接" width="560px">
      <el-alert v-if="linkInfo.status === 'USED'" type="success" :closable="false"
                title="已激活" class="mb12" />
      <el-alert v-else-if="linkInfo.status !== 'ACTIVE'" type="warning" :closable="false"
                :title="`链接状态：${linkInfo.status}`" class="mb12" />
      <el-form label-width="90px" size="small">
        <el-form-item label="链接">
          <el-input :model-value="fullLink" readonly>
            <template #append>
              <el-button @click="copyLink">复制</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="有效期">
          <span>{{ linkInfo.expireAt || '-' }}</span>
        </el-form-item>
      </el-form>
      <div class="hint">线下送达供应商联系人（企业微信/邮件）；7 天未激活自动失效。</div>
      <template #footer>
        <el-button :disabled="linkInfo.status === 'USED'" type="warning"
                   @click="doRegenerate">重新生成（旧链立即失效）</el-button>
        <el-button @click="linkVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="申请单详情" size="520px">
      <template v-if="detail.apply">
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="编号">{{ detail.apply.applyNo }}</el-descriptions-item>
          <el-descriptions-item label="企业">{{ detail.apply.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="信用代码">{{ detail.apply.taxNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusName(detail.apply.status) }}</el-descriptions-item>
          <el-descriptions-item label="风险">{{ detail.apply.riskLevel }}</el-descriptions-item>
          <el-descriptions-item label="建档">{{ detail.apply.supplierId || '未建档' }}</el-descriptions-item>
          <el-descriptions-item label="关闭原因">{{ detail.apply.closeReason || '-' }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="mt12">证照</h4>
        <el-table :data="detail.certs || []" size="small" border>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ certName(row.certType) }}</template>
          </el-table-column>
          <el-table-column prop="certNo" label="编号" />
          <el-table-column prop="expireDate" label="有效期" width="110" />
          <el-table-column prop="attachCount" label="附件" width="60" align="center" />
        </el-table>
        <h4 v-if="detail.approval" class="mt12">审批日志</h4>
        <pre v-if="detail.approval" class="approval-log">{{ JSON.stringify(detail.approval, null, 1) }}</pre>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/scm/partner-onboarding'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ current: 1, size: 10, status: '', keyword: '' })

const formVisible = ref(false)
const form = reactive({
  id: '', supplierName: '', taxNo: '', contactName: '', contactPhone: '',
  contactEmail: '', mainCategory: '', estAnnualAmount: null, bankName: '',
  paymentTerms: '', certs: []
})

const detailVisible = ref(false)
const detail = ref({})
const linkVisible = ref(false)
const linkInfo = ref({})
const linkApplyId = ref('')

const uploadUrl = '/api/common/attachments'
const uploadHeaders = computed(() => ({
  Authorization: `Bearer ${localStorage.getItem('token') || ''}`
}))
const fullLink = computed(() => {
  const base = location.origin
  return linkInfo.value.activatePath ? base + linkInfo.value.activatePath : ''
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const { data } = await api.applyPage(query)
    rows.value = data?.records || []
    total.value = Number(data?.total || 0)
  } finally {
    loading.value = false
  }
}

function onPage(p) { query.current = p; load() }

function openCreate() {
  Object.assign(form, {
    id: '', supplierName: '', taxNo: '', contactName: '', contactPhone: '',
    contactEmail: '', mainCategory: '', estAnnualAmount: null, bankName: '',
    paymentTerms: '', certs: []
  })
  formVisible.value = true
}

async function openDetail(row) {
  const { data } = await api.applyDetail(row.id)
  detail.value = data || {}
  detailVisible.value = true
}

async function saveApply() {
  if (!form.supplierName || !form.taxNo) return ElMessage.warning('企业名称与信用代码必填')
  try {
    const { data } = await api.saveApply({ ...form })
    form.id = data?.apply?.id || form.id
    ElMessage.success('草稿已保存（信用代码格式/重复已校验）')
    // 保存证照行（若已填）
    if (form.certs.length) await saveCertRows(true)
    load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  }
}

function addCert() {
  form.certs.push({ id: '', certType: 'LICENSE', certNo: '', expireDate: '', attachCount: 0 })
}

async function saveCertRows(silent) {
  if (!form.id) return ElMessage.warning('请先保存申请单')
  for (const c of form.certs) {
    if (!c.expireDate) return ElMessage.warning('证照有效期必填')
    const { data } = await api.saveCert({ ...c, applyId: form.id })
    c.id = data?.cert?.id || c.id
  }
  if (!silent) ElMessage.success('证照行已保存，可上传附件')
}

function onUploaded(res, c) {
  if (res?.code === 200) {
    c.attachCount = (c.attachCount || 0) + 1
    ElMessage.success('附件已上传')
  } else {
    ElMessage.error(res?.message || '上传失败')
  }
}
function onUploadError() { ElMessage.error('上传失败（须先保存证照行）') }

async function doSubmit(row) {
  try {
    await ElMessageBox.confirm(
      `提交后进入${row.riskLevel === 'HIGH' ? '三级' : '两级'}审核，不可编辑，确认提交？`,
      '提交确认', { type: 'warning' })
    await api.submitApply(row.id)
    ElMessage.success('已提交审批')
    load()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e?.message || '提交失败')
  }
}

async function doWithdraw(row) {
  await api.withdrawApply(row.id)
  ElMessage.success('已撤回（退回草稿，驳回次数保留）')
  load()
}

async function openLink(row) {
  linkApplyId.value = row.id
  const { data } = await api.activateLink(row.id)
  linkInfo.value = data || {}
  linkVisible.value = true
}

async function doRegenerate() {
  const { data } = await api.regenerateLink(linkApplyId.value)
  linkInfo.value = { ...(data || {}), status: 'ACTIVE' }
  ElMessage.success('已重新生成，旧链接立即失效')
}

async function copyLink() {
  try {
    await navigator.clipboard.writeText(fullLink.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

function statusName(s) {
  return { DRAFT: '草稿', APPROVING: '审批中', APPROVED: '已通过',
    REJECTED: '已驳回', CLOSED: '已关闭' }[s] || s
}
function statusType(s) {
  return { DRAFT: 'info', APPROVING: 'warning', APPROVED: 'success',
    REJECTED: 'danger', CLOSED: 'info' }[s] || 'info'
}
function certName(t) {
  return { LICENSE: '营业执照', QMS: '质量体系', ENV: '环保',
    PERMIT: '行业许可', OTHER: '其它' }[t] || t
}
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.cert-row { margin-bottom: 8px; }
.approval-log { background: #f5f7fa; padding: 8px; font-size: 11px; max-height: 240px;
  overflow: auto; white-space: pre-wrap; word-break: break-all; }
</style>
