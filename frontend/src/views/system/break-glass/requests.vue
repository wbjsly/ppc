<template>
  <div class="bg-page">
    <!-- ================= 16.5.1 紧急申请 ================= -->
    <div class="toolbar">
      <el-select v-model="reqQuery.status" placeholder="状态" clearable style="width: 150px"
        @change="loadRequests">
        <el-option label="审批中" value="PENDING_APPROVAL" />
        <el-option label="已生效" value="ACTIVE" />
        <el-option label="已回收" value="RECLAIMED" />
        <el-option label="已审计" value="AUDITED" />
        <el-option label="已驳回" value="REJECTED" />
      </el-select>
      <el-button type="primary" @click="loadRequests">查询</el-button>
      <el-button type="danger" plain @click="openCreate">新建紧急申请</el-button>
      <el-button @click="incidentDlg = true">故障工单</el-button>
      <el-button @click="templateDlg = true">权限模板</el-button>
    </div>
    <el-alert type="warning" :closable="false" style="margin-bottom: 10px"
      title="Break-Glass 是变更窗口约束下唯一的合规临时越权通道：须关联处理中故障工单，双人会签，到期自动回收并生成事后审计报告。" />
    <el-table :data="requests" v-loading="reqLoading" border stripe>
      <el-table-column prop="targetUsername" label="被授权人" width="120" />
      <el-table-column label="模板" min-width="160">
        <template #default="{ row }">{{ row.templateCode }}（{{ row.templateName }}）</template>
      </el-table-column>
      <el-table-column label="关联工单" min-width="180">
        <template #default="{ row }">{{ row.incNo }} {{ row.incidentTitle }}</template>
      </el-table-column>
      <el-table-column prop="durationHours" label="时长(h)" width="80" align="center" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="stType(row.status)" size="small">{{ stLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情/签署</el-button>
          <el-button v-if="row.status === 'ACTIVE'" link type="warning"
            @click="doReclaim(row)">提前归还</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建申请对话框 -->
    <el-dialog v-model="createDlg" title="新建 Break-Glass 紧急申请" width="620px">
      <el-form label-width="110px">
        <el-form-item label="被授权人" required>
          <el-select v-model="form.targetUserId" filterable placeholder="选择用户" style="width: 100%">
            <el-option v-for="u in users" :key="u.id"
              :label="u.username + (u.nickName ? '（' + u.nickName + '）' : '')" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="故障工单" required>
          <el-select v-model="form.incidentId" placeholder="仅列处理中工单" style="width: 100%">
            <el-option v-for="i in processingIncidents" :key="i.id"
              :label="i.incNo + ' ' + i.title" :value="i.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="权限模板" required>
          <el-select v-model="form.templateId" placeholder="选择启用模板" style="width: 100%">
            <el-option v-for="t in activeTemplates" :key="t.id"
              :label="t.code + '（' + t.name + '）'" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请时长" required>
          <el-input-number v-model="form.durationHours" :min="1" :max="maxHours" />
          <span class="muted" style="margin-left: 8px">小时（上限 {{ maxHours }}）</span>
        </el-form-item>
        <el-form-item label="录屏声明" required>
          <el-radio-group v-model="form.screenDeclare">
            <el-radio label="YES">需要录屏</el-radio>
            <el-radio label="NO">不需要（说明理由）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处置说明" required>
          <el-input v-model="form.reason" type="textarea" :rows="3"
            placeholder="≥10 字：故障现象、预期操作、影响范围" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDlg = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitCreate">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- 工单管理对话框 -->
    <el-dialog v-model="incidentDlg" title="故障工单管理" width="720px">
      <div class="toolbar">
        <el-button type="primary" size="small" @click="incidentCreateDlg = true">新建工单</el-button>
      </div>
      <el-table :data="incidents" border size="small">
        <el-table-column prop="incNo" label="编号" width="160" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PROCESSING' ? 'warning' : 'info'" size="small">
              {{ incLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'PROCESSING'">
              <el-button link type="success" @click="transition(row, 'RESOLVED')">解决</el-button>
              <el-button link type="info" @click="transition(row, 'CLOSED')">关闭</el-button>
            </template>
            <span v-else class="muted">已完结</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 新建工单对话框 -->
    <el-dialog v-model="incidentCreateDlg" title="新建故障工单" width="480px">
      <el-form label-width="80px">
        <el-form-item label="编号" required>
          <el-input v-model="incForm.incNo" placeholder="INC-YYYYMMDD-XXX" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="incForm.title" placeholder="故障现象简述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="incidentCreateDlg = false">取消</el-button>
        <el-button type="primary" @click="saveIncident">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模板管理对话框 -->
    <el-dialog v-model="templateDlg" title="紧急权限模板" width="720px">
      <el-alert type="info" :closable="false" style="margin-bottom: 10px"
        title="模板为最小可用角色集合，禁止包含特权角色（ROLE_ADMIN / 审批节点角色等）。" />
      <el-table :data="templates" border size="small">
        <el-table-column prop="code" label="编码" width="180" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column label="角色集合" min-width="180">
          <template #default="{ row }">{{ fmtRoles(row.rolesJson) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'danger'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 详情/签署对话框（跨页共用） -->
    <BgDetailDialog />
  </div>
</template>

<script setup>
/**
 * 16.5.1 紧急申请（拆分自原 Break-Glass 操作台）：申请台账 / 新建申请 / 工单与模板维护 / 提前归还。
 * 公共数据、申请列表与详情签署状态见 useBreakGlass.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/break-glass'
import { useBreakGlass } from './useBreakGlass'
import { fmtRoles, stType, stLabel, incLabel } from './bg-helpers'
import BgDetailDialog from './_parts/BgDetailDialog.vue'

const {
  users, incidents, templates, maxHours,
  processingIncidents, activeTemplates, loadCommon,
  requests, reqLoading, reqQuery, loadRequests,
  openDetail
} = useBreakGlass()

const createDlg = ref(false)
const incidentDlg = ref(false)
const incidentCreateDlg = ref(false)
const templateDlg = ref(false)
const submitting = ref(false)
const form = ref(blankForm())
const incForm = ref({ incNo: '', title: '' })

function blankForm() {
  return { targetUserId: '', templateId: '', incidentId: '', durationHours: 2,
    screenDeclare: 'YES', reason: '' }
}

function openCreate() {
  form.value = blankForm()
  createDlg.value = true
}

async function submitCreate() {
  const f = form.value
  if (!f.targetUserId || !f.templateId || !f.incidentId || !f.reason) {
    ElMessage.error('被授权人 / 工单 / 模板 / 处置说明必填')
    return
  }
  if (f.reason.trim().length < 10) {
    ElMessage.error('处置说明不少于 10 字')
    return
  }
  submitting.value = true
  try {
    await api.bgSubmit(f)
    ElMessage.success('已提交，进入双人会签（值班经理 + 安全管理员）')
    createDlg.value = false
    loadRequests()
  } finally {
    submitting.value = false
  }
}

async function doReclaim(row) {
  await ElMessageBox.confirm('确认提前归还紧急权限？角色将立即回收并生成事后审计报告。', '提前归还',
    { type: 'warning' })
  await api.bgReclaim(row.id)
  ElMessage.success('已归还，审计报告已生成')
  loadRequests()
}

// ---------- 工单/模板管理 ----------
async function saveIncident() {
  if (!incForm.value.incNo || !incForm.value.title) {
    ElMessage.error('编号与标题必填')
    return
  }
  await api.incidentSave(incForm.value)
  ElMessage.success('工单已创建（处理中）')
  incidentCreateDlg.value = false
  incForm.value = { incNo: '', title: '' }
  const { data } = await api.incidentList()
  incidents.value = data || []
}

async function transition(row, status) {
  await api.incidentTransition(row.id, status)
  ElMessage.success('已流转')
  const { data } = await api.incidentList()
  incidents.value = data || []
}

onMounted(async () => {
  await loadCommon()
  loadRequests()
})
</script>

<style scoped>
.bg-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
</style>
