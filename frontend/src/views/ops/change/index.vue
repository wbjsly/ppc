<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- 18.1.1 变更申请 -->
      <el-tab-pane label="变更申请" name="apply">
        <el-form :model="form" label-width="110px" style="max-width: 640px">
          <el-form-item label="变更标题" required>
            <el-input v-model="form.title" placeholder="如：升级订单导入接口至 v2.14.4" />
          </el-form-item>
          <el-form-item label="变更类型">
            <el-select v-model="form.changeType" style="width: 200px">
              <el-option label="普通变更" value="NORMAL" />
              <el-option label="紧急修复" value="EMERGENCY_FIX" />
              <el-option label="灰度发布" value="GRAY" />
            </el-select>
          </el-form-item>
          <el-form-item label="影响范围">
            <el-input v-model="form.affectScope" placeholder="如：销售订单导入接口" />
          </el-form-item>
          <el-form-item label="预计耗时(分)">
            <el-input-number v-model="form.estimateMin" :min="5" :max="720" />
          </el-form-item>
          <el-form-item label="回滚耗时(分)">
            <el-input-number v-model="form.rollbackMin" :min="0" :max="360" />
          </el-form-item>
          <el-form-item label="回滚脚本" required>
            <el-input v-model="plan.script" placeholder="回滚脚本/步骤（审批门禁 BR-5.8-01）" />
          </el-form-item>
          <el-form-item label="演练有效" required>
            <el-switch v-model="plan.drillValid" />
          </el-form-item>
          <el-form-item label="回滚耗时(预案)" required>
            <el-input-number v-model="plan.estimateMin" :min="1" :max="360" />
          </el-form-item>
          <el-button type="primary" @click="submit">提交申请</el-button>
          <span style="margin-left: 12px; color: #909399; font-size: 12px">
            结账保障期（1~5 日）非紧急变更将被阻断（BR-5.8-14）
          </span>
        </el-form>
      </el-tab-pane>

      <!-- 18.1.2 变更日历（按状态排布台账） -->
      <el-tab-pane label="变更日历" name="calendar">
        <el-table :data="tickets" border size="small">
          <el-table-column prop="ticketNo" label="单号" width="180" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="changeType" label="类型" width="110" />
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ stLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="inWindow" label="窗口" width="80">
            <template #default="{ row }">{{ row.inWindow === '1' ? '窗口内' : row.inWindow === '0' ? '窗口外' : '—' }}</template>
          </el-table-column>
          <el-table-column prop="createDate" label="提交时间" width="160">
            <template #default="{ row }">{{ fmt(row.createDate) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 18.1.3 窗口发布（启动管控） -->
      <el-tab-pane label="窗口发布" name="window">
        <el-alert type="info" :closable="false" style="margin-bottom: 10px"
                  title="启动校验：窗口内且剩余时间 ≥ (预计+回滚)×1.5 分钟方可启动（BR-5.8-06）" />
        <el-table :data="approvable" border size="small">
          <el-table-column prop="ticketNo" label="单号" width="180" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="estimateMin" label="预计(分)" width="90" />
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button size="small" type="success" @click="approve(row)">审批</el-button>
              <el-button size="small" type="primary" :disabled="row.status !== 'APPROVED'"
                         @click="start(row)">启动</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 18.1.4 失败回滚 -->
      <el-tab-pane label="失败回滚" name="rollback">
        <el-table :data="executable" border size="small">
          <el-table-column prop="ticketNo" label="单号" width="180" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">{{ stLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" type="warning" @click="rollback(row)">回滚</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 18.1.5 回滚原因台账 -->
      <el-tab-pane label="回滚原因" name="reason">
        <el-table :data="rolledBack" border size="small">
          <el-table-column prop="ticketNo" label="单号" width="180" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="rollbackReason" label="回滚原因" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" type="primary" :disabled="!!row.rollbackReason"
                         @click="close(row)">关闭工单</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="warning" :closable="false" style="margin-top: 10px"
                  title="回滚原因为空时关闭将被阻断（BR-5.8-04）" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { submitChangeApi, approveChangeApi, startChangeApi, rollbackChangeApi,
  closeChangeApi, listChangeApi } from '@/api/ops/ops-sla'

const route = useRoute()
const tab = ref(route.meta.tab || 'apply')
const tickets = ref([])

const form = ref({ title: '', changeType: 'NORMAL', affectScope: '', estimateMin: 60, rollbackMin: 15 })
const plan = ref({ script: '', drillValid: true, estimateMin: 10 })

const approvable = computed(() => tickets.value.filter(t => t.status === 'SUBMITTED' || t.status === 'APPROVED'))
const executable = computed(() => tickets.value.filter(t => t.status === 'EXECUTING' || t.status === 'APPROVED'))
const rolledBack = computed(() => tickets.value.filter(t => t.status === 'ROLLED_BACK'))

onMounted(load)
async function load() {
  const r = await listChangeApi({ page: 1, size: 50 })
  tickets.value = r.data || []
}

async function submit() {
  const body = { ...form.value, rollbackPlanJson: JSON.stringify(plan.value) }
  await submitChangeApi(body)
  ElMessage.success('已提交')
  load()
}

async function approve(row) {
  try {
    await approveChangeApi(row.id)
    ElMessage.success('审批通过（窗口判定已记录）')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '审批被阻断（预案三件套不齐 BR-5.8-01）')
  }
}

async function start(row) {
  try {
    const r = await startChangeApi(row.id)
    ElMessage.success(`已启动（窗口剩余 ${r.data.remainingMin} 分钟）`)
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '启动被阻断（窗口剩余不足 BR-5.8-06）')
  }
}

async function rollback(row) {
  const { value } = await ElMessageBox.prompt('回滚原因（将写入台账）', '回滚确认', { type: 'warning' })
  await rollbackChangeApi(row.id, value)
  ElMessage.success('已回滚')
  load()
}

async function close(row) {
  let reason = row.rollbackReason
  if (!reason) {
    const r = await ElMessageBox.prompt('须填写回滚原因方可关闭（BR-5.8-04）', '关闭工单', { type: 'warning' })
    reason = r.value
  }
  await closeChangeApi(row.id, reason)
  ElMessage.success('已关闭')
  load()
}

function stLabel(s) {
  return { SUBMITTED: '待审批', APPROVED: '已审批', EXECUTING: '执行中', ROLLED_BACK: '已回滚', CLOSED: '已关闭' }[s] || s
}
function stTag(s) {
  return { SUBMITTED: 'info', APPROVED: 'primary', EXECUTING: 'warning', ROLLED_BACK: 'danger', CLOSED: 'success' }[s] || 'info'
}
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
</script>
