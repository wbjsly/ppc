<template>
  <div class="page-wrap">
    <!-- 待办（可签节点） -->
    <el-card shadow="never" class="mb12">
      <div class="card-head mb12">
        <h4 style="margin:0">资质审核工作台（12.1.2）</h4>
        <div>
          <el-radio-group v-model="tab" size="small" @change="load">
            <el-radio-button value="todo">我的待办</el-radio-button>
            <el-radio-button value="all">全部实例</el-radio-button>
          </el-radio-group>
          <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>
        </div>
      </div>

      <!-- 待办列表 -->
      <el-table v-if="tab === 'todo'" :data="todos" size="small" border v-loading="loading">
        <el-table-column prop="bizTitle" label="申请单" min-width="200">
          <template #default="{ row }">{{ row.title || row.bizTitle || row.bizId }}</template>
        </el-table-column>
        <el-table-column prop="nodeName" label="节点" width="140" />
        <el-table-column prop="roleRequired" label="所需角色" width="160" />
        <el-table-column prop="createTime" label="提交时间" width="160" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openSign(row, 'pass')">通过</el-button>
            <el-button size="small" type="danger" @click="openSign(row, 'reject')">驳回</el-button>
            <el-button size="small" text @click="viewLogs(row)">日志</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 全部实例 -->
      <el-table v-else :data="history" size="small" border v-loading="loading">
        <el-table-column prop="bizId" label="申请单 ID" width="240" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'APPROVED' ? 'success'
              : row.status === 'REJECTED' ? 'danger' : 'warning'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="发起时间" width="160" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" text @click="viewLogs(row)">日志</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 催办升级说明 -->
    <el-card shadow="never">
      <h4 style="margin-top:0">审核时限（BR-4.9-10 语义）</h4>
      <ul class="rules">
        <li>提交后 <b>5 个工作日</b>内未完成任一节点 → 系统自动催办</li>
        <li>超 <b>3 个工作日</b>未完成 → 升级至上级（通知中心）</li>
        <li>驳回整单退回草稿可改后重提；累计驳回 <b>≥3 次</b>自动关闭申请单</li>
        <li>高风险（&gt;100 万/年 或 关键物料品类）强制三级：采购 → 质量 → 合规</li>
        <li>普通供应商两级：采购 → 质量</li>
      </ul>
      <div class="hint">超时扫描由审批底座既有 sweepTimeouts（72h 提醒/7 天升级）与
        结项催办同模式覆盖；驳回计数在驳回时累加。</div>
    </el-card>

    <!-- 签署对话框 -->
    <el-dialog v-model="signVisible" :title="signAction === 'pass' ? '审核通过' : '审核驳回'"
               width="480px">
      <el-alert v-if="signAction === 'reject'" type="warning" :closable="false" class="mb12"
                title="驳回将整单退回草稿（驳回意见必填 ≥2 字，计数累计 ≥3 次自动关闭）" />
      <el-input v-model="opinion" type="textarea" :rows="4"
                :placeholder="signAction === 'pass' ? '审核意见（建议填写）' : '驳回原因（必填 ≥2 字）'" />
      <template #footer>
        <el-button @click="signVisible = false">取消</el-button>
        <el-button :type="signAction === 'pass' ? 'primary' : 'danger'"
                   @click="doSign">确认</el-button>
      </template>
    </el-dialog>

    <!-- 日志 -->
    <el-dialog v-model="logVisible" title="审批日志" width="640px">
      <pre class="log-view">{{ JSON.stringify(logs, null, 1) }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/scm/partner-onboarding'

const tab = ref('todo')
const loading = ref(false)
const todos = ref([])
const history = ref([])

const signVisible = ref(false)
const signAction = ref('pass')
const signRow = ref(null)
const opinion = ref('')

const logVisible = ref(false)
const logs = ref({})

onMounted(load)

async function load() {
  loading.value = true
  try {
    if (tab.value === 'todo') {
      const { data } = await api.approvalTodo()
      // 只看伙伴入驻相关待办
      todos.value = (Array.isArray(data) ? data : []).filter(t =>
        String(t.bizType || '').toUpperCase() === 'PARTNER_ADMISSION' ||
        String(t.title || t.bizTitle || '').includes('伙伴引入'))
    } else {
      const { data } = await api.approvalHistory()
      history.value = Array.isArray(data) ? data : []
    }
  } finally {
    loading.value = false
  }
}

function openSign(row, action) {
  signRow.value = row
  signAction.value = action
  opinion.value = ''
  signVisible.value = true
}

async function doSign() {
  const taskId = signRow.value.taskId || signRow.value.id
  if (signAction.value === 'reject' && (opinion.value || '').trim().length < 2) {
    return ElMessage.warning('驳回原因必填（≥2 字）')
  }
  try {
    if (signAction.value === 'pass') {
      await api.approvalPass(taskId, opinion.value || '同意')
    } else {
      await api.approvalReject(taskId, opinion.value)
    }
    ElMessage.success(signAction.value === 'pass' ? '已通过' : '已驳回（退回草稿）')
    signVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

async function viewLogs(row) {
  const bizId = row.bizId || row.id
  const { data } = await api.approvalHistory()
  const inst = (Array.isArray(data) ? data : []).find(x => x.bizId === bizId)
  logs.value = inst || { bizId, note: '未找到实例（可能已关闭）' }
  logVisible.value = true
}
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.rules { padding-left: 18px; line-height: 1.9; margin: 0; }
.hint { color: #909399; font-size: 12px; }
.log-view { background: #f5f7fa; padding: 8px; font-size: 11px; max-height: 400px;
  overflow: auto; white-space: pre-wrap; word-break: break-all; }
</style>
