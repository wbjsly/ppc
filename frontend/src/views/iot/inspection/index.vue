<template>
  <div>
    <PdaShell :title="'设备巡检 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <el-radio-group v-model="tab" size="small" style="margin-bottom:10px">
        <el-radio-button value="exec">巡检执行</el-radio-button>
        <el-radio-button value="records">点检记录</el-radio-button>
        <el-radio-button value="abnormal">异常标记</el-radio-button>
        <el-radio-button value="photo">拍照留证</el-radio-button>
      </el-radio-group>

      <!-- 13.3.1 巡检执行：任务列表 + 签到 -->
      <template v-if="tab === 'exec'">
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
      </template>

      <!-- 13.3.2 点检记录 -->
      <template v-if="tab === 'records'">
        <el-table :data="records" size="small" v-loading="loading">
          <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
          <el-table-column prop="itemsJson" label="点检项" show-overflow-tooltip />
          <el-table-column label="异常" width="60">
            <template #default="{ row }">
              <el-tag v-if="row.abnormalFlag === '1'" size="small" type="danger">异常</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="submitAt" label="提交时间" width="150" />
        </el-table>
      </template>

      <!-- 13.3.3 异常标记（联动 13.4 异常单） -->
      <template v-if="tab === 'abnormal'">
        <el-table :data="exceptions" size="small" v-loading="loading">
          <el-table-column prop="excNo" label="异常单" width="150" />
          <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
          <el-table-column prop="severity" label="级别" width="70" />
          <el-table-column prop="status" label="状态" width="80" />
        </el-table>
      </template>

      <!-- 13.3.4 拍照留证（选中任务上传） -->
      <template v-if="tab === 'photo'">
        <el-alert type="info" :closable="false" style="margin-bottom:8px"
                  title="异常点检项须拍照留证后方可提交（缺图 422 补拍提示）" />
        <el-input v-model="photoUrl" placeholder="照片 URL（演示：直接填写）" />
        <el-button size="small" type="primary" style="margin-top:8px"
                   :disabled="!current" @click="submitWithPhoto">带图提交当前任务</el-button>
      </template>

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
 * 13.3 设备巡检四叶（add-iot-mobile）：巡检执行（扫码签到）/ 点检记录 /
 * 异常标记（联动异常单）/ 拍照留证。套 PdaShell 仿真壳。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import {
  inspectGenerateApi, inspectCheckinApi, inspectSubmitApi, inspectListApi,
  inspectOverdueApi, exceptionListApi,
} from '@/api/iot/inspect'

const route = useRoute()
const tab = computed(() => route.meta.tab || 'exec')
const tabTitle = computed(() => route.meta.title || '设备巡检')

const loading = ref(false)
const tasks = ref([])
const records = ref([])
const exceptions = ref([])
const current = ref(null)
const photoUrl = ref('')
const submitDlg = ref(false)
const items = ref([{ item: '', value: '', abnormal: false }])
const result = ref(null)

async function loadTasks() {
  loading.value = true
  try {
    const r = await inspectListApi({ status: 'OPEN' })
    tasks.value = r.data || []
  } finally {
    loading.value = false
  }
}
async function loadRecords() {
  loading.value = true
  try {
    const r = await inspectListApi({ status: 'SUBMITTED' })
    records.value = r.data || []
  } finally {
    loading.value = false
  }
}
async function loadExceptions() {
  try {
    const r = await exceptionListApi({ excType: 'EQUIPMENT' })
    exceptions.value = r.data || []
  } catch (e) { exceptions.value = [] }
}

function onScan(code) {
  // 扫码即签到当前首个待执行任务（扫码框 → 设备签到）
  const t = tasks.value.find(x => x.status === 'OPEN' && !x.checkinAt)
  if (!t) {
    ElMessage.warning('无待签到任务')
    return
  }
  checkin(t, code)
}

async function checkin(row, code) {
  try {
    await inspectCheckinApi(row.id, code || row.deviceNo)
    ElMessage.success('签到成功')
    loadTasks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '签到失败')
  }
}

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

async function submitWithPhoto() {
  try {
    await inspectSubmitApi(current.value.id, {
      itemsJson: JSON.stringify(items.value.length ? items.value
        : [{ item: '外观', value: '正常', abnormal: false }]),
      photoUrls: photoUrl.value ? `[\"${photoUrl.value}\"]` : null,
    })
    ElMessage.success('带图提交成功')
    loadTasks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  }
}

function doSync() {
  ElMessage.info('联网状态下队列已实时同步')
}

onMounted(() => {
  loadTasks()
  if (tab.value === 'records') loadRecords()
  if (tab.value === 'abnormal') loadExceptions()
})
</script>
