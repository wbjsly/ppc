<template>
  <div>
    <PdaShell :title="'异常上报 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 一键上报（FR-4.11-3-1 要点① 30 秒内完成） -->
      <el-card shadow="never" style="margin-bottom:10px">
        <template #header><span style="font-weight:600">一键上报</span></template>
        <el-form label-width="76px" size="small">
          <el-form-item label="类型">
            <el-radio-group v-model="form.excType">
              <el-radio-button value="QUALITY">质量</el-radio-button>
              <el-radio-button value="EQUIPMENT">设备</el-radio-button>
              <el-radio-button value="SAFETY">安全</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="严重程度">
            <el-radio-group v-model="form.severity">
              <el-radio-button value="GENERAL">一般</el-radio-button>
              <el-radio-button value="SEVERE">严重</el-radio-button>
              <el-radio-button value="URGENT">紧急</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="2"
                      placeholder="现象、时间、地点、影响范围" />
          </el-form-item>
          <el-form-item label="照片">
            <el-input v-model="form.photoUrls" placeholder="照片 URL（全景+特写）" />
          </el-form-item>
          <el-form-item>
            <el-button type="danger" :loading="submitting" @click="report">提交上报</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 异常单列表（当前类型） -->
      <el-table :data="list" size="small" v-loading="loading">
        <el-table-column prop="excNo" label="单号" width="150" />
        <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
        <el-table-column prop="severity" label="级别" width="70" />
        <el-table-column label="联动" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.linkWoId" size="small">已建工单</el-tag>
            <el-tag v-else-if="row.stopCmdAt" size="small" type="danger">已停机</el-tag>
            <el-tag v-else-if="row.confirmFlag === '0'" size="small" type="warning">待确认</el-tag>
            <el-tag v-else size="small" type="info">—</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button v-if="row.confirmFlag === '0'" size="small" type="warning" link
                       @click="confirm(row)">现场确认</el-button>
            <el-button v-if="row.status !== 'CLOSED'" size="small" type="success" link
                       @click="dispose(row)">闭环</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top:8px">
        <el-button size="small" @click="scanEscalate">30min 超时升级扫描</el-button>
      </div>

      <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:8px"
                :title="result.title" :description="result.desc" />
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.4 异常上报三叶（add-iot-mobile）：质量异常/设备故障/安全事件。
 * 一键上报 + 联动状态展示（冻结/建单/停机）+ IoT 现场确认门禁 + 处置闭环。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import {
  exceptionReportApi, exceptionConfirmApi, exceptionDisposeApi,
  exceptionListApi, exceptionEscalateApi,
} from '@/api/iot/inspect'

const route = useRoute()
const tab = computed(() => route.meta.tab || 'quality')
const tabTitle = computed(() => route.meta.title || '异常上报')

const TYPE_MAP = { quality: 'QUALITY', equipment: 'EQUIPMENT', safety: 'SAFETY' }

const loading = ref(false)
const submitting = ref(false)
const list = ref([])
const result = ref(null)
const form = ref({
  excType: TYPE_MAP[route.meta.tab] || 'QUALITY',
  severity: 'GENERAL',
  description: '',
  photoUrls: '',
  deviceNo: 'PDA-WEB',
  source: 'MANUAL',
})

async function load() {
  loading.value = true
  try {
    const r = await exceptionListApi({ excType: form.value.excType })
    list.value = r.data || []
  } finally {
    loading.value = false
  }
}

async function report() {
  submitting.value = true
  try {
    const r = await exceptionReportApi({
      ...form.value,
      photoUrls: form.value.photoUrls ? `[\"${form.value.photoUrls}\"]` : null,
    })
    const d = r.data || {}
    result.value = {
      type: d.merged ? 'warning' : 'success',
      title: d.merged ? '已合并至同班次同类首单' : `上报成功 ${d.excNo || ''}`,
      desc: [
        d.pendingConfirm ? 'IoT 自动上报：待现场确认后触发联动' : '',
        d.linkage && d.linkage !== 'GATED_AWAIT_FIELD_CONFIRM' ? `联动：${d.linkage}` : '',
        d.urgentNotified ? `紧急通知已推送：${d.urgentNotified}` : '',
      ].filter(Boolean).join('；'),
    }
    form.value.description = ''
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '上报失败')
  } finally {
    submitting.value = false
  }
}

async function confirm(row) {
  try {
    const r = await exceptionConfirmApi(row.id)
    result.value = { type: 'success', title: '现场确认完成',
      desc: `联动：${r.data?.linkage || ''}` }
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '确认失败')
  }
}

async function dispose(row) {
  try {
    await exceptionDisposeApi(row.id, { result: '已处置', remark: '移动端闭环' })
    ElMessage.success('处置闭环')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '闭环失败')
  }
}

async function scanEscalate() {
  const r = await exceptionEscalateApi()
  ElMessage.info(`升级 ${r.data?.escalated || 0} 条：${(r.data?.details || []).join('、')}`)
}

function onScan(code) {
  // 扫码即带设备号上报
  form.value.deviceNo = code
  report()
}

function doSync() {
  ElMessage.info('联网状态下队列已实时同步')
}

watch(() => route.meta.tab, t => {
  form.value.excType = TYPE_MAP[t] || 'QUALITY'
  load()
})

onMounted(load)
</script>
