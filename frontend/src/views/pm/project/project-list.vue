<template>
  <div class="pm-container">
    <!-- ==================== 列表 ==================== -->
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="title">项目立项（11.3.1）</span>
          <div class="actions">
            <el-select v-model="filters.status" placeholder="状态" clearable style="width: 130px;" @change="load">
              <el-option label="草稿" value="DRAFT" />
              <el-option label="审批中" value="APPROVING" />
              <el-option label="已启动" value="ACTIVE" />
            </el-select>
            <el-select v-model="filters.projType" placeholder="项目类型" clearable style="width: 120px;" @change="load">
              <el-option label="研发" value="研发" />
              <el-option label="实施" value="实施" />
              <el-option label="运维" value="运维" />
            </el-select>
            <el-input v-model="filters.keyword" placeholder="项目名称" clearable style="width: 180px;" @change="load" />
            <el-button type="primary" :icon="Plus" @click="openCreate">新建项目</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="projectNo" label="项目编号" width="130">
          <template #default="{ row }">{{ row.projectNo || '—（启动后生成）' }}</template>
        </el-table-column>
        <el-table-column prop="name" label="项目名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="projType" label="类型" width="70" />
        <el-table-column label="路径" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.deliveryPath === 'EXT' ? 'primary' : 'info'">
              {{ row.deliveryPath === 'EXT' ? '外部交付' : '内部' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="合同/内部单" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.deliveryPath === 'EXT' ? (row.contractNo || row.contractId) : row.internalFormNo }}
          </template>
        </el-table-column>
        <el-table-column prop="pmName" label="项目经理" width="100" />
        <el-table-column label="起止" width="180">
          <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="基线金额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.baselineAmount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ==================== 创建/编辑弹窗 ==================== -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑项目' : '新建项目'" width="640px">
      <el-form label-width="110px">
        <el-form-item label="项目名称" required>
          <el-input v-model="form.name" maxlength="128" placeholder="项目名称" />
        </el-form-item>
        <el-form-item label="项目类型" required>
          <el-radio-group v-model="form.projType">
            <el-radio v-for="t in ['研发', '实施', '运维']" :key="t" :value="t">{{ t }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="交付路径" required>
          <el-radio-group v-model="form.deliveryPath">
            <el-radio value="EXT">外部交付（关联合同）</el-radio>
            <el-radio value="INTERNAL">内部项目（立项单号）</el-radio>
          </el-radio-group>
        </el-form-item>
        <!-- 双路径动态字段 -->
        <el-form-item v-if="form.deliveryPath === 'EXT'" label="销售合同" required>
          <el-select v-model="form.contractId" filterable placeholder="选择已签订（SIGNED）合同" style="width: 100%;">
            <el-option v-for="c in signedContracts" :key="c.id" :label="`${c.contractNo} ${c.title || ''}`" :value="c.id" />
          </el-select>
          <div class="hint">仅展示状态为「已签订」的合同；未签订合同立项将被 422 阻断</div>
        </el-form-item>
        <el-form-item v-if="form.deliveryPath === 'INTERNAL'" label="内部立项单号" required>
          <el-input v-model="form.internalFormNo" maxlength="64" placeholder="内部立项审批单号（自由文本）" />
        </el-form-item>
        <el-form-item label="项目经理" required>
          <el-select v-model="form.pmUserId" filterable placeholder="选择已授权人员" style="width: 100%;">
            <el-option v-for="u in pmUsers" :key="u.id" :label="u.nickName || u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="起止日期" required>
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="开始" end-placeholder="结束" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="项目目标">
          <el-input v-model="form.goal" type="textarea" :rows="2" maxlength="1000" />
        </el-form-item>
        <el-form-item label="项目范围">
          <el-input v-model="form.scope" type="textarea" :rows="2" maxlength="2000" />
        </el-form-item>
        <el-form-item label="分摊率">
          <el-input-number v-model="form.fenplanRate" :min="0" :max="1" :step="0.01" :precision="4" />
          <span class="hint">间接成本 = 前五项合计 × 分摊率（默认 10%）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 详情抽屉 ==================== -->
    <el-drawer v-model="detailVisible" :title="detail.name" size="520px">
      <template v-if="detail.id">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="项目编号">{{ detail.projectNo || '—（启动后生成）' }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.projType }}</el-descriptions-item>
          <el-descriptions-item label="路径">
            {{ detail.deliveryPath === 'EXT' ? '外部交付' : '内部项目' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.deliveryPath === 'EXT'" label="合同">
            {{ detail.contractNo || detail.contractId }}
          </el-descriptions-item>
          <el-descriptions-item v-else label="内部立项单号">{{ detail.internalFormNo }}</el-descriptions-item>
          <el-descriptions-item label="项目经理">{{ detail.pmName }}</el-descriptions-item>
          <el-descriptions-item label="起止">{{ detail.startDate }} ~ {{ detail.endDate }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.status)">{{ statusName(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="基线锁定">
            {{ detail.baselineLocked === '1' ? '已锁定' : '未锁定' }}
          </el-descriptions-item>
          <el-descriptions-item label="基线金额">{{ fmtMoney(detail.baselineAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.rejectOpinion" label="驳回意见">
            {{ detail.rejectOpinion }}
          </el-descriptions-item>
        </el-descriptions>

        <template v-if="detail.wbsStat">
          <el-divider content-position="left">WBS 概览</el-divider>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="阶段">{{ detail.wbsStat.phases }}</el-descriptions-item>
            <el-descriptions-item label="工作包">{{ detail.wbsStat.packages }}</el-descriptions-item>
            <el-descriptions-item label="任务">{{ detail.wbsStat.tasks }}</el-descriptions-item>
          </el-descriptions>
        </template>

        <el-divider content-position="left">IP 归属协议附件（11.8 接手门禁，本期仅归档位）</el-divider>
        <el-upload :show-file-list="false" :http-request="doUploadIp" multiple>
          <el-button size="small" type="primary" plain>上传协议</el-button>
        </el-upload>
        <div v-for="a in ipAttachments" :key="a.id" class="attach-row">
          <el-link :href="attachmentDownloadUrl(a.id)" target="_blank" type="primary">{{ a.fileName }}</el-link>
          <span class="hint">{{ a.createDate }}</span>
        </div>
        <el-empty v-if="!ipAttachments.length" description="暂无附件" :image-size="60" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  projectList, projectCreate, projectUpdate, projectDelete, projectDetail,
  uploadAttachment, attachmentList, attachmentDownloadUrl
} from '@/api/pm/project'
import request from '@/utils/request'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const filters = ref({ status: '', projType: '', keyword: '' })

const formVisible = ref(false)
const form = ref({})
const dateRange = ref([])

const detailVisible = ref(false)
const detail = ref({})
const ipAttachments = ref([])

const signedContracts = ref([])
const pmUsers = ref([])

onMounted(() => {
  load()
  loadContracts()
  loadUsers()
})

async function load() {
  loading.value = true
  try {
    const params = {}
    if (filters.value.status) params.status = filters.value.status
    if (filters.value.projType) params.projType = filters.value.projType
    if (filters.value.keyword) params.keyword = filters.value.keyword
    const res = await projectList(params)
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadContracts() {
  try {
    const res = await request({ url: '/api/crm/contracts', method: 'get', params: { status: 'SIGNED' } })
    signedContracts.value = res.data || []
  } catch { signedContracts.value = [] }
}

async function loadUsers() {
  try {
    const res = await request({ url: '/api/system/users', method: 'get', params: { status: '1' } })
    pmUsers.value = res.data || []
  } catch { pmUsers.value = [] }
}

function openCreate() {
  form.value = { id: null, projType: '研发', deliveryPath: 'EXT', fenplanRate: 0.1 }
  dateRange.value = []
  formVisible.value = true
}

function openEdit(row) {
  form.value = { ...row, fenplanRate: Number(row.fenplanRate) }
  dateRange.value = [row.startDate, row.endDate]
  formVisible.value = true
}

async function submitForm() {
  const f = form.value
  if (!f.name) { ElMessage.error('项目名称必填'); return }
  if (!f.deliveryPath) { ElMessage.error('交付路径必填'); return }
  if (f.deliveryPath === 'EXT' && !f.contractId) { ElMessage.error('外部交付项目须关联合同'); return }
  if (f.deliveryPath === 'INTERNAL' && !f.internalFormNo) { ElMessage.error('内部项目须填写立项单号'); return }
  if (!f.pmUserId) { ElMessage.error('项目经理必填'); return }
  if (!dateRange.value || dateRange.value.length !== 2) { ElMessage.error('起止日期必填'); return }
  const payload = { ...f, startDate: dateRange.value[0], endDate: dateRange.value[1] }
  saving.value = true
  try {
    if (f.id) {
      await projectUpdate(f.id, payload)
      ElMessage.success('已保存')
    } else {
      await projectCreate(payload)
      ElMessage.success('项目已创建（草稿）')
    }
    formVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`删除项目「${row.name}」？将级联删除 WBS 与预算行。`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await projectDelete(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

async function openDetail(row) {
  const res = await projectDetail(row.id)
  detail.value = res.data || {}
  detailVisible.value = true
  loadIpAttachments()
}

async function loadIpAttachments() {
  try {
    const res = await attachmentList('PM_IP_AGREEMENT', detail.value.id)
    ipAttachments.value = res.data || []
  } catch { ipAttachments.value = [] }
}

async function doUploadIp({ file }) {
  try {
    await uploadAttachment(file, 'PM_IP_AGREEMENT', detail.value.id)
    ElMessage.success('已上传')
    loadIpAttachments()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

function statusType(s) {
  return s === 'ACTIVE' ? 'success' : (s === 'APPROVING' ? 'warning' : 'info')
}
function statusName(s) {
  return { DRAFT: '草稿', APPROVING: '审批中', ACTIVE: '已启动' }[s] || s
}
function fmtMoney(v) {
  return v == null ? '—' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2 })
}
</script>

<style scoped>
.pm-container { padding: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.title { font-weight: bold; font-size: 16px; }
.actions { display: flex; gap: 8px; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.attach-row { display: flex; justify-content: space-between; align-items: center; padding: 4px 0; }
</style>
