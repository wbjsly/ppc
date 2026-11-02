<template>
  <div>
    <PdaShell :title="'设备巡检 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 13.3.1 巡检执行：任务列表 + 签到 -->
      <el-table :data="tasks" size="small" v-loading="loading">
        <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
        <el-table-column prop="planCode" label="计划" width="120" show-overflow-tooltip />
        <el-table-column label="状态" width="76">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'SUBMITTED' ? 'success' : 'info'">
              {{ row.status === 'SUBMITTED' ? '已提交' : '待执行' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="超时" width="60">
          <template #default="{ row }">
            <el-tag v-if="row.overdueFlag === '1'" size="small" type="danger">漏检</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'OPEN'">
              <el-button size="small" type="primary" link
                         :disabled="!!row.checkinAt" @click="checkin(row)">签到</el-button>
              <el-button size="small" type="success" link @click="openSubmit(row)">点检</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top:8px;display:flex;gap:8px">
        <el-button size="small" @click="genTasks">从巡检计划生成</el-button>
        <el-button size="small" @click="scanOverdue">超时扫描</el-button>
      </div>

      <!-- 点检录入对话框 -->
      <el-dialog v-model="submitDlg" title="逐点检项录入" width="420px">
        <div v-for="(it, i) in items" :key="i" style="display:flex;gap:6px;margin-bottom:6px">
          <el-input v-model="it.item" placeholder="点检项" style="width:35%" />
          <el-input v-model="it.value" placeholder="数值/结论" style="width:35%" />
          <el-checkbox v-model="it.abnormal">异常</el-checkbox>
        </div>
        <el-button size="small" @click="items.push({ item: '', value: '', abnormal: false })">加项</el-button>
        <template #footer>
          <el-button @click="submitDlg = false">取消</el-button>
          <el-button type="primary" @click="submitItems">提交</el-button>
        </template>
      </el-dialog>

      <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:8px"
                :title="result.title" :description="result.desc" />
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.3.1 巡检执行（拆分自原设备巡检操作台）：任务列表 + 扫码/按钮签到 + 逐点检项录入。
 * 任务列表、当前任务、点检项、结果条与扫码签到见 useInspection.js。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import { inspectGenerateApi, inspectSubmitApi, inspectOverdueApi } from '@/api/iot/inspect'
import { useInspection } from './useInspection'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '设备巡检')

const {
  loading, tasks, current, photoUrl, items, result,
  loadTasks, checkin, onScan, doSync,
} = useInspection()

const submitDlg = ref(false)

async function genTasks() {
  const r = await inspectGenerateApi()
  ElMessage.success(`生成 ${r.data?.created || 0} 个巡检任务`)
  loadTasks()
}

async function scanOverdue() {
  const r = await inspectOverdueApi()
  ElMessage.info(`超时标记 ${r.data?.overdueMarked || 0} 项`)
  loadTasks()
}

function openSubmit(row) {
  current.value = row
  items.value = [{ item: '', value: '', abnormal: false }]
  submitDlg.value = true
}

async function submitItems() {
  try {
    const abnormal = items.value.some(i => i.abnormal)
    const r = await inspectSubmitApi(current.value.id, {
      itemsJson: JSON.stringify(items.value),
      photoUrls: abnormal ? (photoUrl.value ? `[\"${photoUrl.value}\"]` : null) : '[]',
    })
    submitDlg.value = false
    result.value = {
      type: r.data?.exceptionId ? 'warning' : 'success',
      title: '点检提交成功',
      desc: r.data?.exceptionId ? '异常项已自动生成异常上报单' : '',
    }
    loadTasks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  }
}

onMounted(() => {
  loadTasks()
})
</script>
