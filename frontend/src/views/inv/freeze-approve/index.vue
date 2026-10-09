<template>
  <div class="page">
    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item>
        <el-button type="primary" @click="loadTodo">刷新待办</el-button>
      </el-form-item>
      <span class="tip">
        仅显示当前角色可签的解冻待办（BIZ_TYPE=Unfreeze）；跨类型签署由审批底座拒绝（C-4.4-05）。
        冻结审批仍在「审批中心」。
      </span>
    </el-form>

    <el-table :data="todos" v-loading="loading" border stripe>
      <el-table-column prop="apprNo" label="审批单号" width="150" />
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
      <el-table-column prop="roleRequired" label="签署角色" width="180" />
      <el-table-column prop="nodeName" label="节点" width="120" />
      <el-table-column prop="applyBy" label="申请人" width="110" />
      <el-table-column prop="applyDate" label="申请时间" width="155">
        <template #default="{ row }">{{ fmtTime(row.applyDate) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <el-button link type="success" size="small" @click="pass(row)">通过</el-button>
          <el-button link type="danger" size="small" @click="reject(row)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && todos.length === 0" description="暂无可签解冻待办" />

    <!-- 通过意见（可空默认同意） -->
    <el-dialog v-model="passVisible" title="解冻审批 - 通过" width="480px">
      <el-alert v-if="current" :title="current.title" type="info" :closable="false"
        style="margin-bottom: 12px" />
      <el-input v-model="passOpinion" type="textarea" :rows="3" placeholder="审批意见（可空，默认同意）" />
      <template #footer>
        <el-button @click="passVisible = false">取消</el-button>
        <el-button type="success" :loading="saving" @click="doPass">确认通过</el-button>
      </template>
    </el-dialog>

    <!-- 驳回原因（≥2 字） -->
    <el-dialog v-model="rejectVisible" title="解冻审批 - 驳回" width="480px">
      <el-alert v-if="current" :title="current.title" type="warning" :closable="false"
        style="margin-bottom: 12px" />
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="500"
        placeholder="驳回原因（必填，至少 2 字；驳回后继续冻结）" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 解冻单详情（来源冻结单信息） -->
    <el-drawer v-model="detailVisible" title="解冻单详情" size="520px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="冻结单号">{{ detail.freezeNo }}</el-descriptions-item>
          <el-descriptions-item label="类型">
            <el-tag :type="detail.freezeType === 'QUALITY' ? 'warning' : 'danger'" size="small">
              {{ detail.freezeType === 'QUALITY' ? '质量' : '财务' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="物料/批次">
            {{ detail.itemCode }} / {{ detail.batchNo || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="冻结数量">{{ detail.qty }}</el-descriptions-item>
          <el-descriptions-item label="冻结原因">{{ detail.reason }}</el-descriptions-item>
          <el-descriptions-item label="处理结果">{{ detail.releaseResult || '-' }}</el-descriptions-item>
          <el-descriptions-item label="解冻依据">{{ detail.releaseBasis || '-' }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi } from '@/api/qms/approval'
import { getFreezesApi } from '@/api/inv/freeze'

// 4.9.3 解冻审批：todo 过滤 bizType=Unfreeze（底座已按当前角色聚合），
// 页内 pass/reject；后端零改动，跨类型 403 由底座 ROLE_REQUIRED 兜底。
const loading = ref(false)
const saving = ref(false)
const todos = ref([])

const passVisible = ref(false)
const rejectVisible = ref(false)
const detailVisible = ref(false)
const current = ref(null)
const detail = ref(null)
const passOpinion = ref('')
const rejectReason = ref('')

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadTodo() {
  loading.value = true
  try {
    const res = await getQmsApprovalTodoApi()
    const all = res.data || []
    // 仅解冻（Unfreeze）；ESCALATE 通知节点不可签，剔除
    todos.value = all.filter(t => t.bizType === 'Unfreeze' && t.kind !== 'ESCALATE')
  } catch (e) {
    console.warn('[freeze-approve] todo failed', e)
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  current.value = row
  detail.value = null
  detailVisible.value = true
  try {
    // bizId = 冻结单 ID；全量取回后按 ID 精确命中（列表接口无按 ID 查询）
    const res = await getFreezesApi({})
    const list = res.data || []
    detail.value = list.find(f => f.id === row.bizId) || null
    if (!detail.value) ElMessage.warning('冻结单不存在或已被清理')
  } catch (e) {
    console.warn('[freeze-approve] detail failed', e)
  }
}

function pass(row) {
  current.value = row
  passOpinion.value = ''
  passVisible.value = true
}

async function doPass() {
  saving.value = true
  try {
    await passQmsApprovalApi(current.value.taskId, passOpinion.value)
    ElMessage.success('已通过，解冻执行中')
    passVisible.value = false
    loadTodo()
  } catch (e) {
    console.warn('[freeze-approve] pass failed', e)
  } finally {
    saving.value = false
  }
}

function reject(row) {
  current.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function doReject() {
  if (!rejectReason.value || rejectReason.value.trim().length < 2) {
    ElMessage.warning('驳回原因至少 2 字')
    return
  }
  saving.value = true
  try {
    await rejectQmsApprovalApi(current.value.taskId, rejectReason.value.trim())
    ElMessage.success('已驳回，冻结保持生效')
    rejectVisible.value = false
    loadTodo()
  } catch (e) {
    console.warn('[freeze-approve] reject failed', e)
  } finally {
    saving.value = false
  }
}

onMounted(loadTodo)
</script>

<style scoped>
.page { padding: 4px; }
.filter-bar { margin-bottom: 4px; }
.tip { color: #909399; font-size: 12px; line-height: 32px; margin-left: 8px; }
</style>
