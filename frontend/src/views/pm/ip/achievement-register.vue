<template>
  <div class="ip-ach">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        研发成果登记（FR-4.8-6-1/2，C-4.8-07）：成果须关联 WBS 任务级节点（未关联 L1 硬阻断）。
        参与人员从工时归集自动提取预填（可增删调整），完成日期默认取任务最后工时日。
        权属判定由系统按 IP 归属协议自动计算（独立→公司 100%；合作→按比例共有；委托→归委托方）；
        合作/委托项目缺协议 L1 阻断，独立研发缺协议 L4 提示放行。
        提交后进入权属确认流（研发负责人确认）。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header><b>登记成果</b></template>
      <el-form label-width="110px" size="default">
        <el-row :gutter="16">
          <el-col :span="10">
            <el-form-item label="项目" required>
              <el-select v-model="form.projectId" filterable placeholder="选择项目"
                style="width: 100%;" @change="onProject">
                <el-option v-for="p in projects" :key="p.id"
                  :label="`${p.projectNo || ''} ${p.name}`" :value="p.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="成果类型" required>
              <el-select v-model="form.type" style="width: 100%;">
                <el-option value="PATENT" label="专利" />
                <el-option value="SOFT_CP" label="软件著作权" />
                <el-option value="TRADE_SECRET" label="技术秘密" />
                <el-option value="TECH_DOC" label="技术文档" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="完成日期">
              <el-date-picker v-model="form.completeDate" type="date" value-format="YYYY-MM-DD"
                placeholder="默认取任务最后工时日" style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="10">
            <el-form-item label="关联 WBS 任务" required>
              <el-select v-model="form.wbsId" filterable placeholder="选择任务级节点（必填 L1）"
                style="width: 100%;" @change="onWbs">
                <el-option v-for="t in taskOptions" :key="t.id" :label="`${t.wbsCode} ${t.name}`" :value="t.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="14">
            <el-form-item label="成果名称" required>
              <el-input v-model="form.name" placeholder="成果名称" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="成果描述">
          <el-input v-model="form.description" type="textarea" :rows="2"
            placeholder="建议 20 字以上（<20 字 L4 提示，不阻断）" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header>
        <b>参与人员（工时自动提取，可调整）</b>
        <el-button size="small" style="margin-left: 12px;" :disabled="!form.wbsId"
          @click="doExtract">从工时提取</el-button>
      </template>
      <el-table :data="form.members" size="small" border>
        <el-table-column prop="empId" label="工号" width="140" />
        <el-table-column prop="empName" label="姓名" width="140" />
        <el-table-column prop="hours" label="工时(小时)" width="120" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeMember(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="field-hint">参与人员以快照落库，后续工时变动不影响已登记成果的清单（仲裁证据链）</div>
    </el-card>

    <el-card>
      <el-button type="primary" :loading="submitting" :disabled="!form.projectId || !form.wbsId"
        @click="doSubmit">提交确认</el-button>
      <el-button :disabled="!form.projectId" @click="doDraft">存草稿</el-button>
    </el-card>

    <!-- 待确认清单（研发负责人视角） -->
    <el-card style="margin-top: 12px;">
      <template #header><b>待确认成果（研发负责人审核）</b></template>
      <el-table :data="pending" size="small" border>
        <el-table-column prop="achieveNo" label="成果编号" width="150" />
        <el-table-column prop="name" label="成果名称" min-width="160" />
        <el-table-column prop="type" label="类型" width="110">
          <template #default="{ row }">{{ typeName(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="ownershipResult" label="权属判定" min-width="140" />
        <el-table-column prop="status" label="状态" width="130">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="doConfirm(row)">确认</el-button>
            <el-button link type="warning" size="small" @click="openObject(row)">有异议</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 异议对话框 -->
    <el-dialog v-model="objVisible" title="权属确认异议" width="480px">
      <el-form label-width="90px">
        <el-form-item label="异议类型" required>
          <el-select v-model="objForm.objectionType" style="width: 100%;">
            <el-option value="OBJ_MULTI" label="多方权属主张（自动创建仲裁单）" />
            <el-option value="OBJ_UNILATERAL" label="单方争议（职务发明疑义等，转法务复核）" />
          </el-select>
        </el-form-item>
        <el-form-item label="异议说明" required>
          <el-input v-model="objForm.reason" type="textarea" :rows="3" placeholder="异议依据与说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="objVisible = false">取消</el-button>
        <el-button type="primary" @click="doObject">提交异议</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/ip'
import { projectList } from '@/api/pm/project'
import { wbsTree } from '@/api/pm/wbs'

const projects = ref([])
const taskOptions = ref([])
const pending = ref([])
const submitting = ref(false)
const objVisible = ref(false)
const objForm = ref({ achieveId: '', objectionType: 'OBJ_MULTI', reason: '' })

const form = ref({
  projectId: '', wbsId: '', type: 'PATENT', name: '', description: '',
  completeDate: '', members: []
})

const TYPE_NAMES = { PATENT: '专利', SOFT_CP: '软著', TRADE_SECRET: '技术秘密', TECH_DOC: '技术文档' }
const STATUS_NAMES = { DRAFT: '草稿', PENDING_CONFIRM: '待确认', CONFIRMED: '已确认', LEGAL_REVIEW: '法务复核' }
const typeName = t => TYPE_NAMES[t] || t
const statusName = s => STATUS_NAMES[s] || s

async function loadProjects() {
  const { data } = await projectList({})
  projects.value = (data || []).map(p => p.project || p)
}

async function loadPending() {
  const { data } = await api.achievementList({ status: 'PENDING_CONFIRM' })
  pending.value = data?.items || []
}

async function onProject(pid) {
  form.value.wbsId = []
  taskOptions.value = []
  if (!pid) return
  const { data } = await wbsTree(pid)
  const walk = (nodes) => (nodes || []).forEach(n => {
    if (n.level === 3) taskOptions.value.push(n)
    if (n.children) walk(n.children)
  })
  walk(data)
}

async function onWbs(wbsId) {
  if (!wbsId) return
  // 参与人预填 + 完成日期默认
  const { data } = await api.extractMembers(wbsId)
  form.value.members = (data || []).map(m => ({ ...m }))
}

function removeMember(row) {
  form.value.members = form.value.members.filter(m => m.empId !== row.empId)
}

async function doExtract() {
  if (!form.value.wbsId) return
  const { data } = await api.extractMembers(form.value.wbsId)
  form.value.members = (data || []).map(m => ({ ...m }))
  ElMessage.success(`已提取 ${form.value.members.length} 名参与人员`)
}

async function doSubmit() { await doSave('SUBMIT') }
async function doDraft() { await doSave('DRAFT') }

async function doSave(action) {
  submitting.value = true
  try {
    const { data } = await api.saveAchievement({ ...form.value, action })
    if (data?.l4Warning) ElMessage.warning(data.l4Warning)
    else ElMessage.success(action === 'SUBMIT' ? '已提交进入权属确认' : '草稿已保存')
    form.value = { projectId: form.value.projectId, wbsId: '', type: 'PATENT', name: '', description: '', completeDate: '', members: [] }
    await loadPending()
  } finally {
    submitting.value = false
  }
}

async function doConfirm(row) {
  await api.confirmAchievement(row.id, {})
  ElMessage.success('已确认，IP 资产档案自动生成')
  await loadPending()
}

function openObject(row) {
  objForm.value = { achieveId: row.id, objectionType: 'OBJ_MULTI', reason: '' }
  objVisible.value = true
}

async function doObject() {
  const { data } = await api.objectAchievement(objForm.value.achieveId, objForm.value)
  if (data?.arbitration) ElMessage.success('异议已提交，仲裁单已自动创建')
  else ElMessage.success('异议已提交，转法务复核')
  objVisible.value = false
  await loadPending()
}

onMounted(() => { loadProjects(); loadPending() })
</script>

<style scoped>
.hint { color: #909399; font-size: 13px; line-height: 1.7; }
.field-hint { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
</style>
