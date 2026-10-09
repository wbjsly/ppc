<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="差异审批（4.11.3）：CountDiff 待签 + 盘点差异台账 + 重点监控仓位"
      description="超容差差异（>0.5%）挂单节点仓库主管审批；通过后同事务自动执行库存调整（按正负拆 ADJUST_IN/OUT）并生成凭证，驳回回待复盘。与拣货差异（4.7.4）视图隔离。" />

    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane label="待我审批" name="todo">
        <div class="toolbar">
          <el-button @click="loadTodo">刷新</el-button>
          <span class="tip">仅显示 CountDiff 待签（底座按角色强制，同人不可签）</span>
        </div>
        <el-table :data="todos" v-loading="loading" size="small" border>
          <el-table-column prop="apprNo" label="审批单号" width="150" />
          <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
          <el-table-column prop="nodeName" label="节点" width="170" />
          <el-table-column prop="applyBy" label="申请人" width="110" />
          <el-table-column prop="applyDate" label="申请时间" width="155">
            <template #default="{ row }">{{ fmtTime(row.applyDate) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <el-button link type="success" size="small" @click="openPass(row)">通过</el-button>
              <el-button link type="danger" size="small" @click="openReject(row)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && todos.length === 0" description="暂无待签" />
      </el-tab-pane>

      <el-tab-pane label="差异台账" name="diffs">
        <div class="toolbar">
          <el-select v-model="diffFilters.status" placeholder="状态" clearable style="width: 130px"
            @change="loadDiffs(1)">
            <el-option label="待审批" value="PENDING" />
            <el-option label="已闭环" value="RESOLVED" />
          </el-select>
          <el-input v-model="diffFilters.taskNo" placeholder="任务号" clearable style="width: 170px"
            @clear="loadDiffs(1)" @keyup.enter="loadDiffs(1)" />
          <el-input v-model="diffFilters.itemCode" placeholder="物料" clearable style="width: 140px"
            @clear="loadDiffs(1)" @keyup.enter="loadDiffs(1)" />
          <el-button @click="loadDiffs(1)">查询</el-button>
        </div>
        <el-table :data="diffs" v-loading="loading" size="small" border>
          <el-table-column prop="diffNo" label="差异单号" width="150" />
          <el-table-column prop="taskNo" label="盘点任务" width="170" />
          <el-table-column prop="itemCode" label="物料" width="130" />
          <el-table-column prop="batchNo" label="批次" width="130" />
          <el-table-column label="账面/实盘" width="140">
            <template #default="{ row }">{{ row.expectQty }} / {{ row.actualQty }}</template>
          </el-table-column>
          <el-table-column prop="deltaQty" label="差异" width="90" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'RESOLVED' ? 'success' : 'warning'">
                {{ row.status === 'RESOLVED' ? '已闭环' : '待审批' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resolveNote" label="处理说明" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.resolveNote || '-' }}</template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="diffTotal" :page-size="diffFilters.size"
          :current-page="diffFilters.current"
          @current-change="p => { diffFilters.current = p; loadDiffs() }" />
      </el-tab-pane>

      <el-tab-pane label="重点监控仓位" name="watch">
        <el-table :data="watchList" v-loading="loading" size="small" border>
          <el-table-column prop="binCode" label="仓位" width="160" />
          <el-table-column prop="diffCount" label="超容差次数" width="120" />
          <el-table-column prop="reason" label="说明" min-width="300" show-overflow-tooltip />
        </el-table>
        <el-empty v-if="!loading && watchList.length === 0" description="暂无监控仓位" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="passVisible" title="差异审批 - 通过（将自动执行库存调整）" width="460px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 10px"
        title="通过即按行差异正负拆分执行 ADJUST_IN/ADJUST_OUT 并生成会计凭证，不可撤销" />
      <el-input v-model="passOpinion" type="textarea" :rows="3" placeholder="意见（可空，默认同意）" />
      <template #footer>
        <el-button @click="passVisible = false">取消</el-button>
        <el-button type="success" :loading="acting" @click="doPass">确认通过</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="rejectVisible" title="差异审批 - 驳回（回待复盘）" width="460px">
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="500"
        placeholder="驳回原因（必填，至少 2 字；行标待复盘，重录后可重新提交）" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="acting" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi
} from '@/api/qms/approval'
import { getCountDiffsApi, getCountWatchListApi } from '@/api/inv/count'

// 4.11.3 差异审批：CountDiff 待签（复用 4.9 页内审批范式）+ COUNT 台账 + 监控清单
const tab = ref('todo')
const loading = ref(false)
const acting = ref(false)
const todos = ref([])
const diffs = ref([])
const diffTotal = ref(0)
const watchList = ref([])
const diffFilters = reactive({ status: '', taskNo: '', itemCode: '', current: 1, size: 20 })

const passVisible = ref(false)
const rejectVisible = ref(false)
const passOpinion = ref('')
const rejectReason = ref('')
let currentTask = null

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadTodo() {
  loading.value = true
  try {
    const res = await getQmsApprovalTodoApi()
    todos.value = (res.data || []).filter(t => t.bizType === 'CountDiff' && t.kind !== 'ESCALATE')
  } catch (e) {
    console.warn('[count-approve] todo failed', e)
  } finally {
    loading.value = false
  }
}

async function loadDiffs(page) {
  if (page) diffFilters.current = page
  loading.value = true
  try {
    const res = await getCountDiffsApi({
      status: diffFilters.status || undefined,
      taskNo: diffFilters.taskNo || undefined,
      itemCode: diffFilters.itemCode || undefined,
      current: diffFilters.current, size: diffFilters.size
    })
    diffs.value = res.data?.records || []
    diffTotal.value = Number(res.data?.total || 0)
  } catch (e) {
    console.warn('[count-approve] diffs failed', e)
  } finally {
    loading.value = false
  }
}

async function loadWatch() {
  loading.value = true
  try {
    const res = await getCountWatchListApi()
    watchList.value = res.data || []
  } catch (e) {
    console.warn('[count-approve] watch failed', e)
  } finally {
    loading.value = false
  }
}

function onTab(name) {
  if (name === 'todo') loadTodo()
  else if (name === 'diffs') loadDiffs(1)
  else loadWatch()
}

function openPass(row) {
  currentTask = row
  passOpinion.value = ''
  passVisible.value = true
}

async function doPass() {
  acting.value = true
  try {
    await passQmsApprovalApi(currentTask.taskId, passOpinion.value)
    ElMessage.success('已通过，库存调整已自动执行（台账可见凭证关联）')
    passVisible.value = false
    loadTodo()
    if (tab.value === 'diffs') loadDiffs(1)
  } catch (e) {
    console.warn('[count-approve] pass failed', e)
  } finally {
    acting.value = false
  }
}

function openReject(row) {
  currentTask = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function doReject() {
  if (!rejectReason.value || rejectReason.value.trim().length < 2) {
    ElMessage.warning('驳回原因至少 2 字')
    return
  }
  acting.value = true
  try {
    await rejectQmsApprovalApi(currentTask.taskId, rejectReason.value.trim())
    ElMessage.success('已驳回（行标待复盘，重录后可重新提交）')
    rejectVisible.value = false
    loadTodo()
  } catch (e) {
    console.warn('[count-approve] reject failed', e)
  } finally {
    acting.value = false
  }
}

onMounted(loadTodo)
</script>

<style scoped>
.page { padding: 4px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
