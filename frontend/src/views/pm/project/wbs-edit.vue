<template>
  <div class="pm-container">
    <!-- 项目选择 -->
    <el-card style="margin-bottom: 12px;">
      <div class="pick-row">
        <span class="label">当前项目：</span>
        <el-select v-model="projectId" filterable placeholder="选择项目" style="width: 320px;" @change="onProjectChange">
          <el-option v-for="p in projects" :key="p.id"
            :label="`${p.projectNo || '（草稿）'} ${p.name}`" :value="p.id" />
        </el-select>
        <el-tag v-if="project.baselineLocked === '1'" type="danger" size="small">基线已锁定（WBS 只读）</el-tag>
        <el-tag v-else-if="project.status" :type="project.status === 'ACTIVE' ? 'success' : 'info'" size="small">
          {{ { DRAFT: '草稿', APPROVING: '审批中', ACTIVE: '已启动' }[project.status] || project.status }}
        </el-tag>
        <el-button v-if="projectId" type="primary" plain size="small" @click="goBudget">去预算编制 →</el-button>
      </div>
    </el-card>

    <el-tabs v-model="activeTab" v-if="projectId">
      <!-- ==================== Tab 1：WBS 树 ==================== -->
      <el-tab-pane label="WBS 分解" name="wbs">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>WBS（阶段 → 工作包 → 任务；任务级 = 立项校验底线）</span>
              <div class="actions">
                <el-button size="small" type="success" plain @click="addNode(null, 1)">+ 阶段</el-button>
                <el-button size="small" :disabled="readonly" @click="validateWbs">立项校验</el-button>
              </div>
            </div>
          </template>

          <el-alert v-if="validateErrors.length" type="error" :closable="false" show-icon style="margin-bottom: 10px;">
            <div v-for="(err, i) in validateErrors" :key="i">{{ err }}</div>
          </el-alert>

          <el-tree :data="tree" node-key="id" default-expand-all :expand-on-click-node="false">
            <template #default="{ data }">
              <div class="wbs-node">
                <el-tag size="small" :type="lvType(data.level)" disable-transitions>
                  {{ { 1: '阶段', 2: '工作包', 3: '任务' }[data.level] }}
                </el-tag>
                <span class="code">{{ data.wbsCode }}</span>
                <span class="name">{{ data.name }}</span>
                <span v-if="data.level === 2" class="meta">交付物：{{ data.deliverable || '（缺）' }}</span>
                <span v-if="data.level === 3" class="meta">
                  {{ data.ownerName || '未分配' }} · {{ data.estHours || 0 }}h
                </span>
                <span class="ops">
                  <el-button v-if="data.level < 3" link type="primary" size="small"
                    :disabled="readonly" @click="addNode(data, data.level + 1)">
                    {{ data.level === 1 ? '+ 工作包' : '+ 任务' }}
                  </el-button>
                  <el-button link type="primary" size="small" :disabled="readonly" @click="editNode(data)">编辑</el-button>
                  <el-button link type="danger" size="small" :disabled="readonly" @click="removeNode(data)">删除</el-button>
                </span>
              </div>
            </template>
          </el-tree>
          <el-empty v-if="!tree.length" description="暂无 WBS，从「+ 阶段」开始或到模板页一键生成" />
        </el-card>
      </el-tab-pane>

      <!-- ==================== Tab 2：模板维护 ==================== -->
      <el-tab-pane label="WBS 模板" name="tpl">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>模板库（BR-4.8-13：研发/实施/运维内置种子，可自定义）</span>
              <el-button size="small" type="primary" :icon="Plus" @click="openTpl()">新建模板</el-button>
            </div>
          </template>
          <el-table :data="templates" stripe>
            <el-table-column prop="templateNo" label="编号" width="120" />
            <el-table-column prop="name" label="模板名" min-width="160" />
            <el-table-column prop="projType" label="适用类型" width="90" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
                  {{ row.status === '1' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button link type="success" @click="applyTpl(row)">生成骨架</el-button>
                <el-button link type="primary" @click="openTpl(row)">编辑</el-button>
                <el-button link type="warning" @click="toggleTpl(row)">
                  {{ row.status === '1' ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" @click="removeTpl(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ==================== Tab 3：资源分配 ==================== -->
      <el-tab-pane label="资源分配" name="res">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>人员负载（人×日；超产能/跨项目重叠标黄警示，L4 不阻断）</span>
              <div class="actions">
                <el-date-picker v-model="resRange" type="daterange" value-format="YYYY-MM-DD"
                  :clearable="false" style="width: 260px;" @change="loadRes" />
                <el-button size="small" @click="capVisible = true">产能日历</el-button>
              </div>
            </div>
          </template>
          <el-table :data="loads" v-loading="resLoading" stripe max-height="480">
            <el-table-column prop="userId" label="人员" width="140" />
            <el-table-column label="负载明细" min-width="480">
              <template #default="{ row }">
                <div class="load-cells">
                  <div v-for="d in row.days" :key="d.date" class="load-cell"
                    :class="{ warn: d.warning }" :title="`${d.date} 负载 ${d.loadHours}h / 产能 ${d.capacityHours}h${d.reason ? '（' + d.reason + '）' : ''}`">
                    <div class="date">{{ d.date.slice(5) }}</div>
                    <div class="hours">{{ d.loadHours }}h</div>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="警示" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.warning" type="warning" size="small">{{ row.warningReason }}</el-tag>
                <el-tag v-else type="success" size="small">正常</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loads.length" description="区间内暂无已分配任务（在 WBS 任务上指定负责人后可见）" :image-size="80" />
        </el-card>
      </el-tab-pane>
    </el-tabs>
    <el-empty v-else description="请先选择项目" />

    <!-- ==================== 节点弹窗 ==================== -->
    <el-dialog v-model="nodeVisible" :title="nodeForm.id ? '编辑节点' : '新增节点'" width="540px">
      <el-form label-width="100px">
        <el-form-item label="层级">
          <el-tag>{{ { 1: '阶段', 2: '工作包', 3: '任务' }[nodeForm.level] }}</el-tag>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="nodeForm.name" maxlength="200" />
        </el-form-item>
        <el-form-item v-if="nodeForm.level === 2" label="交付物" required>
          <el-input v-model="nodeForm.deliverable" maxlength="500" placeholder="工作包须有明确交付物定义" />
        </el-form-item>
        <el-form-item v-if="nodeForm.level === 3" label="负责人">
          <el-select v-model="nodeForm.ownerUserId" filterable clearable style="width: 100%;">
            <el-option v-for="u in pmUsers" :key="u.id" :label="u.nickName || u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="nodeForm.level === 3" label="职级">
          <el-select v-model="nodeForm.ownerGrade" clearable placeholder="用于费率计算" style="width: 100%;">
            <el-option label="初级" value="JUNIOR" />
            <el-option label="中级" value="MID" />
            <el-option label="高级" value="SENIOR" />
          </el-select>
        </el-form-item>
        <el-form-item label="计划起止">
          <el-date-picker v-model="nodeDateRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="开始" end-placeholder="结束" style="width: 100%;" />
        </el-form-item>
        <el-form-item v-if="nodeForm.level === 3" label="预估工时">
          <el-input-number v-model="nodeForm.estHours" :min="0" :precision="2" />
          <span class="hint">人工成本 = 工时 × 职级费率（预算页自动算）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="nodeVisible = false">取消</el-button>
        <el-button type="primary" :loading="nodeSaving" @click="saveNode">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 模板弹窗 ==================== -->
    <el-dialog v-model="tplVisible" :title="tplForm.id ? '编辑模板' : '新建模板'" width="640px">
      <el-form label-width="100px">
        <el-form-item label="模板名" required>
          <el-input v-model="tplForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="适用类型" required>
          <el-radio-group v-model="tplForm.projType">
            <el-radio v-for="t in ['研发', '实施', '运维']" :key="t" :value="t">{{ t }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="层级 JSON" required>
          <el-input v-model="tplForm.templateJson" type="textarea" :rows="10"
            placeholder='[{"n":"阶段名","l":1,"c":[{"n":"工作包","l":2,"d":"交付物","c":[{"n":"任务","l":3,"h":40}]}]}]' />
          <div class="hint">短键：n=名称 l=层级(1/2/3) d=交付物(工作包) h=预估工时(任务) c=子节点数组</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tplVisible = false">取消</el-button>
        <el-button type="primary" :loading="tplSaving" @click="saveTpl">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 产能日历弹窗 ==================== -->
    <el-dialog v-model="capVisible" title="产能日历维护（PMO/ADMIN）" width="560px">
      <el-form inline>
        <el-form-item label="人员">
          <el-select v-model="capForm.userId" filterable style="width: 180px;">
            <el-option v-for="u in pmUsers" :key="u.id" :label="u.nickName || u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="capForm.capDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="产能(h)">
          <el-input-number v-model="capForm.hours" :min="0" :max="24" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="capSaving" @click="saveCap">保存</el-button>
        </el-form-item>
      </el-form>
      <div class="hint" style="margin-bottom: 8px;">默认：工作日 8h / 周末 0h；此处仅维护例外（请假/加班）。跨度上限 366 天。</div>
      <el-table :data="capRows" size="small" max-height="280" stripe>
        <el-table-column prop="userId" label="人员" width="140" />
        <el-table-column prop="capDate" label="日期" width="120" />
        <el-table-column prop="hours" label="产能(h)" width="90" />
        <el-table-column prop="remark" label="备注" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { projectList, projectDetail } from '@/api/pm/project'
import {
  wbsTree, wbsCreate, wbsUpdate, wbsDelete, wbsValidate,
  templateList, templateCreate, templateToggle, templateDelete, templateGenerate,
  resourceLoad, capacityList, capacitySave
} from '@/api/pm/wbs'
import request from '@/utils/request'

const route = useRoute()
const router = useRouter()

const projects = ref([])
const projectId = ref('')
const project = ref({})
const activeTab = ref('wbs')
const tree = ref([])
const validateErrors = ref([])
const readonly = ref(false)

const nodeVisible = ref(false)
const nodeSaving = ref(false)
const nodeForm = ref({})
const nodeParent = ref(null)
const nodeDateRange = ref([])

const templates = ref([])
const tplVisible = ref(false)
const tplSaving = ref(false)
const tplForm = ref({})

const resLoading = ref(false)
const loads = ref([])
const resRange = ref([])

const capVisible = ref(false)
const capSaving = ref(false)
const capForm = ref({ userId: '', capDate: '', hours: 8 })
const capRows = ref([])

const pmUsers = ref([])

onMounted(async () => {
  await loadProjects()
  await loadUsers()
  // 支持从预算页/列表页带 projectId 跳转
  const q = route.query.projectId
  if (q) {
    projectId.value = q
    onProjectChange()
  }
})

async function loadProjects() {
  const res = await projectList({})
  projects.value = res.data || []
}

async function loadUsers() {
  try {
    const res = await request({ url: '/api/system/users', method: 'get', params: { status: '1' } })
    pmUsers.value = res.data || []
  } catch { pmUsers.value = [] }
}

async function onProjectChange() {
  if (!projectId.value) return
  const res = await projectDetail(projectId.value)
  project.value = res.data || {}
  readonly.value = project.value.baselineLocked === '1' || project.value.status === 'APPROVING'
  await loadTree()
  loadTemplates()
  initResRange()
  loadRes()
}

async function loadTree() {
  const res = await wbsTree(projectId.value)
  tree.value = res.data || []
}

function addNode(parent, level) {
  nodeParent.value = parent
  nodeForm.value = { id: null, level, name: '', deliverable: '', ownerUserId: '', ownerGrade: '', estHours: 0 }
  nodeDateRange.value = []
  nodeVisible.value = true
}

function editNode(data) {
  nodeParent.value = null
  nodeForm.value = { ...data, estHours: Number(data.estHours) || 0 }
  nodeDateRange.value = data.planStart && data.planEnd ? [data.planStart, data.planEnd] : []
  nodeVisible.value = true
}

async function saveNode() {
  const f = nodeForm.value
  if (!f.name) { ElMessage.error('名称必填'); return }
  if (f.level === 2 && !f.deliverable) { ElMessage.error('工作包须填写交付物'); return }
  const payload = { ...f }
  if (nodeParent.value) payload.parentId = nodeParent.value.id
  payload.projectId = projectId.value
  if (nodeDateRange.value && nodeDateRange.value.length === 2) {
    payload.planStart = nodeDateRange.value[0]
    payload.planEnd = nodeDateRange.value[1]
  }
  nodeSaving.value = true
  try {
    if (f.id) {
      await wbsUpdate(f.id, payload)
    } else {
      await wbsCreate(payload)
    }
    ElMessage.success('已保存')
    nodeVisible.value = false
    loadTree()
    validateErrors.value = []
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  } finally {
    nodeSaving.value = false
  }
}

async function removeNode(data) {
  try {
    await ElMessageBox.confirm(`删除「${data.name}」？子节点与预算行将一并删除。`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await wbsDelete(data.id)
    ElMessage.success('已删除')
    loadTree()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

async function validateWbs() {
  const res = await wbsValidate(projectId.value)
  validateErrors.value = res.data || []
  if (!validateErrors.value.length) {
    ElMessage.success('校验通过：WBS 已分解至任务级，可提交基线审批')
  }
}

async function loadTemplates() {
  const res = await templateList({})
  templates.value = res.data || []
}

function openTpl(row) {
  tplForm.value = row ? { ...row, templateJson: row.templateJson || '' } : { id: null, projType: '研发', templateJson: '' }
  tplVisible.value = true
}

async function saveTpl() {
  const f = tplForm.value
  if (!f.name) { ElMessage.error('模板名必填'); return }
  tplSaving.value = true
  try {
    if (f.id) {
      await templateUpdateReq(f.id, f)
    } else {
      await templateCreate(f)
    }
    ElMessage.success('已保存')
    tplVisible.value = false
    loadTemplates()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  } finally {
    tplSaving.value = false
  }
}

async function templateUpdateReq(id, data) {
  const { templateUpdate } = await import('@/api/pm/wbs')
  return templateUpdate(id, data)
}

async function toggleTpl(row) {
  try {
    await templateToggle(row.id, row.status === '1' ? '0' : '1')
    ElMessage.success('已切换')
    loadTemplates()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

async function removeTpl(row) {
  try {
    await ElMessageBox.confirm(`删除模板「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await templateDelete(row.id)
    ElMessage.success('已删除')
    loadTemplates()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

async function applyTpl(row) {
  let confirmFlag = false
  if (tree.value.length) {
    try {
      await ElMessageBox.confirm('当前项目已有 WBS，从模板生成将整体覆盖，确认？', '覆盖确认', { type: 'warning' })
      confirmFlag = true
    } catch { return }
  }
  try {
    const res = await templateGenerate(projectId.value, row.id, confirmFlag)
    ElMessage.success(`已生成 ${res.data?.created ?? ''} 个节点`)
    loadTree()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

function initResRange() {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - 7)
  end.setDate(end.getDate() + 21)
  const fmt = d => d.toISOString().slice(0, 10)
  resRange.value = [fmt(start), fmt(end)]
}

async function loadRes() {
  if (!resRange.value || resRange.value.length !== 2) return
  resLoading.value = true
  try {
    const res = await resourceLoad({ from: resRange.value[0], to: resRange.value[1] })
    loads.value = res.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  } finally {
    resLoading.value = false
  }
}

async function saveCap() {
  const f = capForm.value
  if (!f.userId || !f.capDate) { ElMessage.error('人员与日期必填'); return }
  capSaving.value = true
  try {
    await capacitySave(f)
    ElMessage.success('已保存')
    loadCapRows()
    loadRes()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  } finally {
    capSaving.value = false
  }
}

async function loadCapRows() {
  const res = await capacityList({})
  capRows.value = res.data || []
}

// 打开产能弹窗时拉行
import { watch } from 'vue'
watch(capVisible, v => { if (v) loadCapRows() })

function goBudget() {
  router.push({ path: '/m/11.3.3', query: { projectId: projectId.value } })
}

function lvType(l) {
  return l === 1 ? 'danger' : (l === 2 ? 'warning' : 'success')
}
</script>

<style scoped>
.pm-container { padding: 4px; }
.pick-row { display: flex; align-items: center; gap: 10px; }
.label { color: #606266; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.actions { display: flex; gap: 8px; }
.wbs-node { display: flex; align-items: center; gap: 8px; width: 100%; font-size: 13px; }
.wbs-node .code { color: #909399; font-family: monospace; }
.wbs-node .name { font-weight: 500; }
.wbs-node .meta { color: #909399; font-size: 12px; }
.wbs-node .ops { margin-left: auto; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.load-cells { display: flex; gap: 4px; flex-wrap: wrap; }
.load-cell { border: 1px solid #e4e7ed; border-radius: 4px; padding: 2px 6px; text-align: center; min-width: 52px; }
.load-cell .date { font-size: 10px; color: #909399; }
.load-cell .hours { font-size: 12px; }
.load-cell.warn { background: #fdf6ec; border-color: #e6a23c; color: #e6a23c; }
</style>
