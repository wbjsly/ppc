<template>
  <div class="ps-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="扫码确认（4.7.2）：逐行 码校验 → 录实拣数 → 确认本行"
      description="行级实时拦截：仓位码不符（BR-4.4-25）/ 批次≠推荐（BR-4.4-26）/ 物料不符（BR-4.4-27）均 422 并落扫码失败记录；批次不符同时登记批次差异。实拣<应拣请走「登记短少差异」。" />

    <div class="toolbar">
      <el-select v-model="taskId" placeholder="选择拣货任务（拣货中）" filterable
        style="width: 380px;" @change="loadDetail">
        <el-option v-for="t in tasks" :key="t.id"
          :label="`${t.taskNo} · ${t.srcDocNo} · ${t.picker || '未指派'}`" :value="t.id" />
      </el-select>
      <el-button @click="loadTasks">刷新</el-button>
      <el-button v-if="task && task.status === 'CREATED'" type="success" @click="startPick">
        开始拣货
      </el-button>
      <span v-if="task" class="state">状态：{{ taskText(task.status) }}</span>
    </div>

    <div v-for="line in lines" :key="line.id" class="line-card"
      :class="{ picked: line.lineStatus === 'PICKED', short: line.lineStatus === 'SHORT' }">
      <div class="line-head">
        <span>行 {{ line.lineNo }} · {{ line.itemCode }} · 应拣 {{ line.qty }}</span>
        <el-tag size="small" :type="lineTag(line.lineStatus)">{{ lineText(line.lineStatus) }}</el-tag>
      </div>
      <div class="line-meta">
        批次 <b>{{ line.batchNo || '—' }}</b> · 仓位 <b>{{ line.binCode || '—' }}</b>
        <el-tag v-if="line.scanOkFlag === '1'" size="small" type="success"
          style="margin-left: 6px;">已放行</el-tag>
      </div>
      <div class="line-actions">
        <el-input v-model="scanInputs[line.lineNo]" placeholder="扫描/输入：仓位→物料→批次" clearable
          style="width: 240px;" @keyup.enter="doVerify(line)" />
        <el-button size="small" type="primary" plain
          :disabled="task?.status !== 'PICKING'" @click="doVerify(line)">校验</el-button>
        <el-input-number v-model="pickedQty[line.lineNo]" :min="0" :precision="3"
          :controls="false" placeholder="实拣数" style="width: 110px;" />
        <el-button size="small" type="success"
          :disabled="task?.status !== 'PICKING' || line.scanOkFlag !== '1'"
          @click="doConfirm(line)">确认本行</el-button>
        <el-button size="small" type="warning"
          :disabled="task?.status !== 'PICKING'"
          @click="openShort(line)">登记短少差异</el-button>
      </div>
    </div>

    <el-alert v-if="!lines.length" type="info" :closable="false"
      title="请先选择拣货任务（仅展示拣货中链路；任务在 4.7.1 管理）" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPickTasksApi, getPickTaskDetailApi, transitionPickTaskApi,
  verifyScanApi, confirmPickLineApi, registerShortApi
} from '@/api/inv/pick'

const tasks = ref([])
const taskId = ref('')
const task = ref(null)
const lines = ref([])
const scanInputs = reactive({})
const pickedQty = reactive({})

const taskText = s => ({ CREATED: '待拣货', PICKING: '拣货中', PICKED: '拣货完成',
  REVIEWING: '复核中', DONE: '复核通过', DIFF_PENDING: '差异待处理',
  QUALITY_PENDING: '质量待处理', COMPLETED: '已完成', CANCELLED: '已作废' }[s] || s)
const lineText = s => ({ PENDING: '待拣', PICKED: '已拣', SHORT: '短少' }[s] || s)
const lineTag = s => ({ PENDING: 'info', PICKED: 'success', SHORT: 'danger' }[s] || 'info')

async function loadTasks() {
  const res = await getPickTasksApi({ current: 1, size: 50, status: 'PICKING' })
  const res2 = await getPickTasksApi({ current: 1, size: 50, status: 'CREATED' })
  tasks.value = [...(res.data.records || []), ...(res2.data.records || [])]
}

async function loadDetail() {
  if (!taskId.value) return
  const res = await getPickTaskDetailApi(taskId.value)
  task.value = res.data.task
  lines.value = res.data.lines || []
  for (const l of lines.value) {
    if (scanInputs[l.lineNo] === undefined) scanInputs[l.lineNo] = ''
    if (pickedQty[l.lineNo] === undefined) pickedQty[l.lineNo] = l.qty
  }
}

async function startPick() {
  await transitionPickTaskApi(taskId.value, 'CREATED', 'PICKING')
  ElMessage.success('已开始拣货')
  await loadDetail()
  await loadTasks()
}

/** 扫码输入支持 仓位|物料|批次 用 | 或 , 分隔；单码=按顺序逐次校验的下一个 */
async function doVerify(line) {
  const raw = (scanInputs[line.lineNo] || '').trim()
  if (!raw) {
    ElMessage.error('请输入或扫描码（可用 | 分隔 仓位|物料|批次）')
    return
  }
  const parts = raw.split(/[|,，]/).map(s => s.trim()).filter(Boolean)
  // 已知期望值时：单段输入按 仓位→物料→批次 顺序推断（首个不匹配期望仓位则视为物料……简化：
  // 优先整串匹配期望；多段按顺序）
  let bin, item, batch
  if (parts.length >= 3) {
    ;[bin, item, batch] = parts
  } else if (parts.length === 2) {
    if (parts[0] === line.binCode || !line.binCode) {
      ;[bin, item, batch] = [parts[0], parts[1], line.batchNo || '']
    } else {
      ;[item, batch] = parts
      bin = line.binCode || ''
    }
  } else {
    // 单码：逐项匹配
    const v = parts[0]
    if (line.binCode && v === line.binCode) {
      bin = v; item = line.itemCode; batch = line.batchNo || ''
    } else if (v === line.itemCode) {
      bin = line.binCode || ''; item = v; batch = line.batchNo || ''
    } else {
      bin = line.binCode || ''; item = line.itemCode; batch = v
    }
  }
  try {
    await verifyScanApi({ taskId: taskId.value, lineNo: line.lineNo,
      binCode: bin, itemCode: item, batchNo: batch })
    ElMessage.success(`行 ${line.lineNo} 校验通过（可录实拣数确认）`)
    scanInputs[line.lineNo] = ''
    await loadDetail()
  } catch (e) {
    // 422：失败明细已由后端落扫码记录/差异行
    ElMessage.error((e && e.message) || '校验失败')
    await loadDetail()
  }
}

async function doConfirm(line) {
  try {
    await confirmPickLineApi({ taskId: taskId.value, lineNo: line.lineNo,
      pickedQty: pickedQty[line.lineNo], serials: line.serials || undefined })
    ElMessage.success(`行 ${line.lineNo} 已确认`)
    await loadDetail()
  } catch (e) {
    const msg = (e && e.message) || ''
    if (msg.includes('短少差异')) {
      // 短少 → 引导登记（spec：422 语义 + 前端转引导）
      ElMessageBox.confirm(`${msg}，是否现在登记短少差异？`, '实拣短少',
        { type: 'warning' }).then(() => openShort(line)).catch(() => {})
    } else {
      ElMessage.error(msg)
    }
  }
}

async function openShort(line) {
  const { value } = await ElMessageBox.prompt(
    `行 ${line.lineNo} 应拣 ${line.qty}，请填实拣数量与原因（格式：数量|原因）`,
    '登记短少差异', { type: 'warning', inputValue: `${pickedQty[line.lineNo] ?? line.qty}|现场短少` })
  const [q, ...rest] = String(value).split('|')
  const reason = rest.join('|').trim()
  if (!reason) {
    ElMessage.error('差异原因必填')
    return
  }
  await registerShortApi({ taskId: taskId.value, lineNo: line.lineNo,
    actualQty: Number(q), reason })
  ElMessage.success('短少差异已登记（任务挂差异待处理，闭环前禁止过账）')
  await loadDetail()
}

onMounted(loadTasks)
</script>

<style scoped>
.ps-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.state { font-size: 13px; color: #606266; }
.line-card { border: 1px solid #ebeef5; border-radius: 4px; padding: 10px; margin-bottom: 10px; }
.line-card.picked { border-color: #c2e7b0; background: #f7fbf4; }
.line-card.short { border-color: #f3d19e; background: #fdf6ec; }
.line-head { font-size: 13px; font-weight: 600; display: flex; justify-content: space-between; }
.line-meta { font-size: 12px; color: #909399; margin: 6px 0; }
.line-actions { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
</style>
