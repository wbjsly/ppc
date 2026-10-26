<template>
  <div class="milestone-manage">
    <el-card style="margin-bottom: 12px;">
      <div class="toolbar">
        <el-select v-model="projectId" placeholder="选择项目（须 ACTIVE）" filterable style="width: 320px;"
          @change="load">
          <el-option v-for="p in projects" :key="p.id" :value="p.id"
            :label="`${p.projectNo || p.name}（${p.status}）`" />
        </el-select>
        <el-button type="primary" :icon="Plus" :disabled="!projectId" @click="openForm()">新建里程碑</el-button>
      </div>
    </el-card>

    <el-card>
      <el-table :data="milestones" v-loading="loading" stripe>
        <el-table-column prop="name" label="里程碑" min-width="160" show-overflow-tooltip />
        <el-table-column prop="phaseName" label="阶段" width="140" show-overflow-tooltip />
        <el-table-column prop="planEndDate" label="计划完成" width="110" />
        <el-table-column label="前序依赖" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.prevMilestoneName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reworkCount" label="返工" width="60" />
        <el-table-column prop="deliverableCount" label="交付物" width="70" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)"
              :disabled="!['PLANNED','REWORK'].includes(row.status)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)"
              :disabled="row.status !== 'PLANNED'">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 创建/编辑弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑里程碑' : '新建里程碑'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="所属阶段" required>
          <el-select v-model="form.wbsId" placeholder="WBS 阶段节点（level=1）" style="width: 100%;">
            <el-option v-for="n in phases" :key="n.id" :value="n.id" :label="n.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" placeholder="如：需求评审通过" />
        </el-form-item>
        <el-form-item label="计划完成日" required>
          <el-date-picker v-model="form.planEndDate" type="date" value-format="YYYY-MM-DD" style="width: 220px;" />
        </el-form-item>
        <el-form-item label="评审标准">
          <el-input v-model="form.reviewCriteria" type="textarea" :rows="2"
            maxlength="1000" placeholder="量化标准（BR-4.8-15）：如「评审缺陷数 ≤3 且无 A 类」" />
        </el-form-item>
        <el-form-item label="前序依赖">
          <el-select v-model="form.prevMilestoneId" clearable placeholder="同项目里程碑（可空）" style="width: 100%;">
            <el-option v-for="m in prevCandidates" :key="m.id" :value="m.id" :label="m.name" />
          </el-select>
          <div class="hint">前序未 DONE 时本里程碑不可发起评审（BR-4.8-16 L1）</div>
        </el-form-item>
        <el-form-item label="审批人">
          <el-input v-model="form.approverName" maxlength="64" placeholder="展示用（审批节点=PMO）" style="width: 220px;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  listMilestones, createMilestone, updateMilestone, deleteMilestone,
  wbsTree, pmProjects
} from '@/api/pm/milestone'

const loading = ref(false)
const saving = ref(false)
const projects = ref([])
const projectId = ref('')
const milestones = ref([])
const phases = ref([])
const formVisible = ref(false)
const form = ref({})

const prevCandidates = computed(() =>
  milestones.value.filter(m => m.id !== form.value.id))

onMounted(async () => {
  const { data } = await pmProjects()
  projects.value = (data || []).filter(p => p.status === 'ACTIVE')
})

async function load() {
  if (!projectId.value) return
  loading.value = true
  try {
    const [ms, tree] = await Promise.all([
      listMilestones(projectId.value),
      wbsTree(projectId.value)
    ])
    milestones.value = ms.data || []
    phases.value = (tree.data || []).filter(n => n.level === 1)
  } finally {
    loading.value = false
  }
}

function openForm(row) {
  form.value = row
    ? { id: row.id, wbsId: row.wbsId, name: row.name, planEndDate: row.planEndDate,
        reviewCriteria: row.reviewCriteria, prevMilestoneId: row.prevMilestoneId,
        approverName: row.approverName }
    : { id: null, wbsId: '', name: '', planEndDate: '', reviewCriteria: '',
        prevMilestoneId: '', approverName: '' }
  formVisible.value = true
}

async function save() {
  if (!form.value.wbsId || !form.value.name || !form.value.planEndDate) {
    ElMessage.error('阶段/名称/计划完成日必填')
    return
  }
  saving.value = true
  try {
    const payload = { ...form.value, projectId: projectId.value }
    if (payload.id) {
      await updateMilestone(payload.id, payload)
    } else {
      await createMilestone(payload)
    }
    ElMessage.success('已保存')
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
    await ElMessageBox.confirm(`删除里程碑「${row.name}」？`, '确认', { type: 'warning' })
  } catch { return }
  try {
    await deleteMilestone(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

function statusText(s) {
  return { PLANNED: '已规划', SUBMITTING: '提交中', REVIEWING: '评审中',
    APPROVED: '待审批', DONE: '已完成', REWORK: '返工中' }[s] || s
}
function statusType(s) {
  return { PLANNED: 'info', SUBMITTING: 'warning', REVIEWING: 'warning',
    APPROVED: 'primary', DONE: 'success', REWORK: 'danger' }[s] || 'info'
}
</script>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; }
.hint { color: #909399; font-size: 12px; margin-top: 2px; }
</style>
