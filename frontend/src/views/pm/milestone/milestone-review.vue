<template>
  <div class="milestone-review">
    <el-tabs v-model="tab">
      <!-- ==================== 待评审队列（REVIEWING） ==================== -->
      <el-tab-pane label="待评审" name="review">
        <el-table :data="reviewing" v-loading="loading" stripe>
          <el-table-column prop="name" label="里程碑" min-width="160" show-overflow-tooltip />
          <el-table-column prop="phaseName" label="阶段" width="130" show-overflow-tooltip />
          <el-table-column prop="planEndDate" label="计划完成" width="110" />
          <el-table-column prop="deliverableCount" label="交付物" width="70" />
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="success" @click="openReview(row, 'PASS')">通过</el-button>
              <el-button link type="warning" @click="openReview(row, 'CONDITIONAL')">有条件</el-button>
              <el-button link type="danger" @click="openReview(row, 'FAIL')">不通过</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ==================== 待审批队列（APPROVED，L2 底座） ==================== -->
      <el-tab-pane label="待审批（PMO）" name="approve">
        <el-table :data="approvalQueue" v-loading="loading" stripe>
          <el-table-column prop="title" label="审批事项" min-width="200" show-overflow-tooltip />
          <el-table-column prop="bizName" label="里程碑" width="160" show-overflow-tooltip />
          <el-table-column prop="applyDate" label="发起时间" width="160" />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button link type="success" @click="pass(row)">通过</el-button>
              <el-button link type="danger" @click="reject(row)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 详情抽屉：交付物版本时间线 + 评审标准 + 审批状态 -->
    <el-drawer v-model="detailVisible" :title="detail.name" size="560px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="阶段">{{ detail.phaseName }}</el-descriptions-item>
        <el-descriptions-item label="计划完成">{{ detail.planEndDate }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small">{{ detail.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="评审标准">{{ detail.reviewCriteria || '—' }}</el-descriptions-item>
        <el-descriptions-item label="返工次数">{{ detail.reworkCount }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.rejectOpinion" label="驳回意见">
          {{ detail.rejectOpinion }}
        </el-descriptions-item>
      </el-descriptions>

      <h4 style="margin: 16px 0 8px;">交付物版本（历史只读）</h4>
      <el-timeline v-if="deliverables.length">
        <el-timeline-item v-for="d in deliverables" :key="d.id" :timestamp="`v${d.version} · ${d.createDate}`">
          <a :href="attachmentDownloadUrl(d.id)" target="_blank">{{ d.fileName }}</a>
          <span style="color: #909399; margin-left: 8px;">上传人 {{ d.createBy }}</span>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无交付物（须先上传才能发起评审）" :image-size="60" />
    </el-drawer>

    <!-- 评审表单弹窗（三态动态必填） -->
    <el-dialog v-model="reviewVisible" :title="`评审：${reviewTarget.name}`" width="520px">
      <el-form label-width="100px">
        <el-form-item label="评审结果">
          <el-radio-group v-model="reviewForm.result" @change="onResultChange">
            <el-radio value="PASS">通过</el-radio>
            <el-radio value="CONDITIONAL">有条件通过</el-radio>
            <el-radio value="FAIL">不通过</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="评审意见">
          <el-input v-model="reviewForm.opinion" type="textarea" :rows="2" maxlength="1000" />
        </el-form-item>
        <template v-if="reviewForm.result === 'CONDITIONAL'">
          <el-form-item label="整改项" required>
            <el-input v-model="reviewForm.rectifyItem" type="textarea" :rows="2" maxlength="500" />
          </el-form-item>
          <el-form-item label="整改期限" required>
            <el-date-picker v-model="reviewForm.rectifyDue" type="date" value-format="YYYY-MM-DD"
              style="width: 220px;" />
          </el-form-item>
        </template>
        <template v-if="reviewForm.result === 'FAIL'">
          <el-form-item label="返工计划" required>
            <el-input v-model="reviewForm.reworkPlan" type="textarea" :rows="3" maxlength="1000"
              placeholder="原因与返工计划（必填，第 2 次不通过自动升级 GM）" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReview">提交评审</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listMilestones, reviewMilestone, milestoneDeliverables, attachmentDownloadUrl,
  approvalTodo, approvalPass, approvalReject
} from '@/api/pm/milestone'

const tab = ref('review')
const loading = ref(false)
const saving = ref(false)
const reviewing = ref([])
const approvalQueue = ref([])

const detailVisible = ref(false)
const detail = ref({})
const deliverables = ref([])

const reviewVisible = ref(false)
const reviewTarget = ref({})
const reviewForm = ref({})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [ms, todo] = await Promise.all([listMilestones(), approvalTodo()])
    reviewing.value = (ms.data || []).filter(m => m.status === 'REVIEWING')
    // 底座待办过滤 PmMilestone，回显里程碑名
    const msById = Object.fromEntries((ms.data || []).map(m => [m.id, m]))
    approvalQueue.value = (todo.data || [])
      .filter(t => t.bizType === 'PmMilestone')
      .map(t => ({ ...t, bizName: msById[t.bizId]?.name || t.bizId }))
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  detail.value = row
  detailVisible.value = true
  const { data } = await milestoneDeliverables(row.id)
  deliverables.value = data || []
}

function openReview(row, result) {
  reviewTarget.value = row
  reviewForm.value = { result, opinion: '', rectifyItem: '', rectifyDue: '', reworkPlan: '' }
  reviewVisible.value = true
}
function onResultChange() { /* 三态动态必填由模板 v-if 控制 */ }

async function submitReview() {
  const f = reviewForm.value
  if (f.result === 'CONDITIONAL' && (!f.rectifyItem || !f.rectifyDue)) {
    ElMessage.error('有条件通过必须填写整改项与整改期限')
    return
  }
  if (f.result === 'FAIL' && !f.reworkPlan) {
    ElMessage.error('不通过必须填写返工计划')
    return
  }
  saving.value = true
  try {
    await reviewMilestone(reviewTarget.value.id, f)
    ElMessage.success(f.result === 'PASS'
      ? '评审通过，已进入 PMO 审批（L2 不可跳过）'
      : '评审已记录')
    reviewVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  } finally {
    saving.value = false
  }
}

async function pass(row) {
  try {
    await approvalPass(row.taskId, '里程碑达成审批通过')
    ElMessage.success('审批通过，里程碑已置 DONE')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

async function reject(row) {
  let reason
  try {
    const { value } = await ElMessageBox.prompt('驳回意见（必填，至少 2 字）', '驳回', {
      inputPattern: /.{2,}/, inputErrorMessage: '至少 2 字'
    })
    reason = value
  } catch { return }
  try {
    await approvalReject(row.taskId, reason)
    ElMessage.success('已驳回，里程碑退回返工')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}
</script>
