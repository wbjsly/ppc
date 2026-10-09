<template>
  <div class="pt-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="拣货任务（4.7.1）：4.6.3 确认推荐后自动生成，主管改派/作废"
      description="状态机 CREATED → PICKING → PICKED → REVIEWING → DONE → COMPLETED（分支 DIFF_PENDING/QUALITY_PENDING）。单据过账且差异闭环后任务自动 COMPLETED；差异闭环前出库过账被门闩拦截（BR-4.4-29）。" />

    <div class="toolbar">
      <el-select v-model="filters.status" placeholder="状态" clearable style="width: 170px;"
        @change="load">
        <el-option label="待拣货" value="CREATED" />
        <el-option label="拣货中" value="PICKING" />
        <el-option label="拣货完成" value="PICKED" />
        <el-option label="复核中" value="REVIEWING" />
        <el-option label="复核通过" value="DONE" />
        <el-option label="差异待处理" value="DIFF_PENDING" />
        <el-option label="质量待处理" value="QUALITY_PENDING" />
        <el-option label="已完成" value="COMPLETED" />
        <el-option label="已作废" value="CANCELLED" />
        <el-option label="冻结挂起" value="PAUSED" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="任务号/单号/拣货员" clearable
        style="width: 200px;" @clear="load" @keyup.enter="load" />
      <el-button @click="load">查询</el-button>
    </div>

    <el-table :data="rows" size="small" border v-loading="loading" @expand-change="loadDetail">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div style="padding: 8px 16px;">
            <el-table :data="row._lines || []" size="mini" border>
              <el-table-column prop="lineNo" label="行" width="50" />
              <el-table-column prop="itemCode" label="物料" width="140" />
              <el-table-column prop="batchNo" label="批次" width="130" />
              <el-table-column prop="binCode" label="仓位" width="100" />
              <el-table-column prop="qty" label="应拣" width="80" />
              <el-table-column prop="pickedQty" label="实拣" width="80" />
              <el-table-column label="行状态" width="90">
                <template #default="{ row: l }">
                  <el-tag size="small" :type="lineTag(l.lineStatus)">{{ lineText(l.lineStatus) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="复核" width="80">
                <template #default="{ row: l }">{{ l.reviewResult || '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="taskNo" label="任务号" width="150" />
      <el-table-column label="来源单据" width="180">
        <template #default="{ row }">{{ typeLabel(row.srcType) }} {{ row.srcDocNo }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="taskTag(row.status)">{{ taskText(row.status) }}</el-tag>
          <div v-if="row.status === 'PAUSED' && row.pauseFreezeNo" class="pause-tip">
            冻结 {{ row.pauseFreezeNo }}
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="picker" label="拣货员" width="100">
        <template #default="{ row }">{{ row.picker || '（未指派）' }}</template>
      </el-table-column>
      <el-table-column prop="createDate" label="创建时间" width="160" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <template v-if="canWh && row.status === 'CREATED'">
            <el-button link type="primary" size="small" @click="openAssign(row)">改派</el-button>
            <el-button link type="danger" size="small" @click="openCancel(row)">作废</el-button>
          </template>
          <el-button v-if="canWh && row.status === 'CREATED'" link type="success" size="small"
            @click="startPick(row)">开始拣货</el-button>
          <!-- 冻结挂起恢复（freeze-management 需求①：来源冻结未解除时后端 422） -->
          <el-button v-if="canWh && row.status === 'PAUSED'" link type="warning" size="small"
            @click="resumeTask(row)">恢复</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top: 10px; justify-content: flex-end;"
      layout="total, prev, pager, next" :total="total" :page-size="20"
      :current-page="filters.current" @current-change="p => { filters.current = p; load() }" />

    <!-- 改派对话框 -->
    <el-dialog v-model="assignDlg" title="改派拣货员" width="380px">
      <el-input v-model="assignPicker" placeholder="拣货员账号/姓名" />
      <template #footer>
        <el-button @click="assignDlg = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doAssign">确认改派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPickTasksApi, getPickTaskDetailApi, assignPickTaskApi,
  cancelPickTaskApi, transitionPickTaskApi, resumePickTaskApi
} from '@/api/inv/pick'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const roles = (userStore.userInfo && userStore.userInfo.roles) || []
const canWh = roles.includes('ROLE_ADMIN') || roles.includes('ROLE_WAREHOUSE')

const loading = ref(false)
const acting = ref(false)
const rows = ref([])
const total = ref(0)
const filters = reactive({ current: 1, status: '', keyword: '' })
const assignDlg = ref(false)
const assignPicker = ref('')
const current = ref(null)

const typeLabel = t => ({ SALES_OUT: '销售出库', MATERIAL_OUT: '领料出库',
  TRANSFER_OUT: '调拨出库', SCRAP_OUT: '报废出库' }[t] || t)
const taskText = s => ({ CREATED: '待拣货', PICKING: '拣货中', PICKED: '拣货完成',
  REVIEWING: '复核中', DONE: '复核通过', DIFF_PENDING: '差异待处理',
  QUALITY_PENDING: '质量待处理', COMPLETED: '已完成', CANCELLED: '已作废',
  PAUSED: '冻结挂起' }[s] || s)
const taskTag = s => ({ CREATED: 'info', PICKING: 'warning', PICKED: 'primary',
  REVIEWING: 'primary', DONE: 'success', DIFF_PENDING: 'danger',
  QUALITY_PENDING: 'danger', COMPLETED: 'success', CANCELLED: 'info',
  PAUSED: 'danger' }[s] || 'info')
const lineText = s => ({ PENDING: '待拣', PICKED: '已拣', SHORT: '短少' }[s] || s)
const lineTag = s => ({ PENDING: 'info', PICKED: 'success', SHORT: 'danger' }[s] || 'info')

async function load() {
  loading.value = true
  try {
    const res = await getPickTasksApi({
      current: filters.current, size: 20,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined
    })
    rows.value = res.data.records || []
    total.value = Number(res.data.total || 0)
  } catch (e) {
    console.warn('[pick-task] load failed', e)
  } finally {
    loading.value = false
  }
}

async function loadDetail(row) {
  if (row._lines) return
  const res = await getPickTaskDetailApi(row.id)
  row._lines = res.data.lines || []
}

function openAssign(row) {
  current.value = row
  assignPicker.value = row.picker || ''
  assignDlg.value = true
}

async function doAssign() {
  if (!assignPicker.value.trim()) {
    ElMessage.error('拣货员必填')
    return
  }
  acting.value = true
  try {
    await assignPickTaskApi(current.value.id, assignPicker.value.trim())
    ElMessage.success('改派成功（前后人员已留痕）')
    assignDlg.value = false
    load()
  } finally {
    acting.value = false
  }
}

async function openCancel(row) {
  const { value } = await ElMessageBox.prompt('作废原因（必填）', '作废拣货任务',
    { type: 'warning' })
  await cancelPickTaskApi(row.id, value)
  ElMessage.success('已作废（可重新确认推荐生成新任务）')
  load()
}

async function startPick(row) {
  await transitionPickTaskApi(row.id, 'CREATED', 'PICKING')
  ElMessage.success('已开始拣货，请到 4.7.2 扫码确认执行')
  load()
}

async function resumeTask(row) {
  acting.value = true
  try {
    await resumePickTaskApi(row.id)
    ElMessage.success('已恢复至挂起前状态（来源冻结须已解冻）')
    load()
  } catch (e) {
    console.warn('[pick-task] resume failed', e)
  } finally {
    acting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.pt-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
.pause-tip { color: #f56c6c; font-size: 11px; line-height: 14px; margin-top: 2px; }
</style>
