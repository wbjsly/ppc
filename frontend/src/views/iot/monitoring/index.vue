<template>
  <div style="padding:16px">
    <el-radio-group v-model="tab" size="small" style="margin-bottom:12px">
      <el-radio-button value="access">设备接入</el-radio-button>
      <el-radio-button value="board">数据看板</el-radio-button>
      <el-radio-button value="alarm">阈值告警</el-radio-button>
      <el-radio-button value="status">状态监控</el-radio-button>
    </el-radio-group>

    <!-- 13.6.1 设备接入：注册 + 连接测试 + 规则配置 -->
    <template v-if="tab === 'access'">
      <el-card shadow="never" style="margin-bottom:10px">
        <template #header><span style="font-weight:600">设备注册（协议优先级 OPC-UA &gt; MQTT &gt; Modbus）</span></template>
        <el-form inline size="small">
          <el-form-item label="设备编号"><el-input v-model="reg.deviceNo" style="width:170px" /></el-form-item>
          <el-form-item label="协议">
            <el-select v-model="reg.protocol" style="width:140px">
              <el-option label="MQTT" value="MQTT" />
              <el-option label="OPC-UA" value="OPC-UA" />
              <el-option label="MODBUS_TCP" value="MODBUS_TCP" />
              <el-option label="MODBUS_RTU" value="MODBUS_RTU" />
            </el-select>
          </el-form-item>
          <el-form-item label="采集秒"><el-input-number v-model="reg.sampleFreqSec" :min="1" style="width:100px" /></el-form-item>
          <el-form-item><el-button type="primary" @click="register">注册</el-button></el-form-item>
        </el-form>
      </el-card>
      <el-table :data="devices" size="small" v-loading="loading">
        <el-table-column prop="deviceNo" label="设备" width="160" />
        <el-table-column prop="protocol" label="协议" width="110" />
        <el-table-column prop="sampleFreqSec" label="采集s" width="70" />
        <el-table-column prop="certStatus" label="证书" width="80" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ONLINE' ? 'success'
              : row.status === 'FAULT' ? 'danger' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="heartbeatAt" label="心跳" width="150" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="connectTest(row)">连接测试</el-button>
            <el-button size="small" link @click="editRule(row)">告警规则</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 13.6.2 数据看板：最新测点 + 手动注入 -->
    <template v-else-if="tab === 'board'">
      <el-form inline size="small">
        <el-form-item label="设备"><el-input v-model="board.deviceNo" style="width:170px" /></el-form-item>
        <el-form-item label="测点">
          <el-select v-model="board.pointCode" style="width:140px">
            <el-option label="temperature" value="temperature" />
            <el-option label="vibration" value="vibration" />
            <el-option label="current" value="current" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadLatest">刷新</el-button>
          <el-button @click="injectAnomaly">注入异常曲线</el-button>
        </el-form-item>
      </el-form>
      <el-descriptions v-if="latest.ts" :column="3" border size="small">
        <el-descriptions-item label="时间">{{ latest.ts }}</el-descriptions-item>
        <el-descriptions-item label="测点">{{ latest.pointCode }}</el-descriptions-item>
        <el-descriptions-item label="值">
          <b :style="{ color: latest.quality === 'SUSPECT' ? '#F56C6C' : '#303133' }">
            {{ latest.val }}
          </b>
        </el-descriptions-item>
        <el-descriptions-item label="质量">{{ latest.quality }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="series" size="small" style="margin-top:10px" max-height="320">
        <el-table-column prop="ts" label="时间" width="180" />
        <el-table-column prop="pointCode" label="测点" width="120" />
        <el-table-column prop="val" label="值" width="110" />
        <el-table-column label="质量" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.quality === 'SUSPECT' ? 'danger' : 'success'">
              {{ row.quality }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="suspects.length" style="margin-top:8px">
        <el-alert type="warning" :closable="false" :title="`可疑数据 ${suspects.length} 条（已被告警过滤）`"
                  :description="suspects.slice(0, 3).map(s => `${s.ts} = ${s.val}`).join('；')" />
      </div>
    </template>

    <!-- 13.6.3 阈值告警：闭环操作 -->
    <template v-else>
      <div style="margin-bottom:8px">
        <el-button size="small" @click="scanEscalate">超时升级扫描</el-button>
        <el-button size="small" type="primary" @click="loadAlarms">刷新</el-button>
      </div>
      <el-table :data="alarms" size="small" v-loading="loading">
        <el-table-column prop="eventNo" label="事件号" width="170" />
        <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
        <el-table-column label="级别" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.alertLevel === 'L1' ? 'danger'
              : row.alertLevel === 'L2' ? 'warning' : 'info'">{{ row.alertLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="mergeCnt" label="合并" width="60" />
        <el-table-column label="停机" width="90">
          <template #default="{ row }">
            <span v-if="row.stopCmdAt">{{ row.stopAckMs }}ms</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="row.status === 'OPEN'">
              <el-button size="small" type="warning" link @click="alarmConfirm(row)">确认</el-button>
              <el-button size="small" link @click="alarmProcessing(row)">处理</el-button>
            </template>
            <el-button v-if="row.status !== 'CLOSED'" size="small" type="success" link
                       @click="alarmClose(row)">关闭</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 13.6.4 状态监控：分布 -->
    <div v-if="tab === 'status'" style="margin-top:12px">
      <el-row :gutter="12">
        <el-col v-for="(v, k) in dist" :key="k" :span="6">
          <el-card shadow="never">
            <el-statistic :title="statusName(k)" :value="Number(v)" />
          </el-card>
        </el-col>
      </el-row>
      <div style="margin-top:10px">
        <el-button size="small" @click="loadDist">刷新分布</el-button>
      </div>
    </div>

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.6 设备监控四叶（add-iot-mobile）：设备接入（注册/连接测试/规则）/
 * 数据看板（最新测点+注入）/ 阈值告警（闭环+升级）/ 状态监控（分布）。
 * 标准宽页（design D3：管理类叶子不用 PDA 壳）。
 */
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  deviceRegisterApi, deviceConnectTestApi, deviceListApi, deviceAlertRuleApi,
  deviceStatusDistApi, telemetryLatestApi, telemetryQueryApi, telemetryInjectApi,
  telemetrySuspectApi, alarmListApi, alarmConfirmApi, alarmProcessingApi,
  alarmCloseApi, alarmScanEscalateApi,
} from '@/api/iot/monitor'

const route = useRoute()
const tab = ref(route.meta.tab || 'access')

const loading = ref(false)
const devices = ref([])
const reg = ref({ deviceNo: '', protocol: 'MQTT', sampleFreqSec: 60 })
const board = ref({ deviceNo: '', pointCode: 'temperature' })
const latest = ref({})
const series = ref([])
const suspects = ref([])
const alarms = ref([])
const dist = ref({})
const result = ref(null)

function ok(title, desc) {
  result.value = { type: 'success', title, desc: desc || '' }
}

function statusName(k) {
  return { ONLINE: '运行中', OFFLINE: '停机', FAULT: '故障', MAINT: '维护中' }[k] || k
}

async function loadDevices() {
  loading.value = true
  try {
    const r = await deviceListApi()
    devices.value = r.data || []
  } finally {
    loading.value = false
  }
}

async function register() {
  try {
    await deviceRegisterApi({ ...reg.value, certSn: 'CERT-' + reg.value.deviceNo })
    ok('注册成功', reg.value.deviceNo)
    reg.value.deviceNo = ''
    loadDevices()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '注册失败')
  }
}

async function connectTest(row) {
  try {
    const r = await deviceConnectTestApi(row.deviceNo)
    const hs = r.data?.handshake || {}
    ok('连接测试通过', `协议 ${hs.protocol || ''} 握手 ${hs.ackMs || 0}ms`)
    loadDevices()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '连接测试失败')
  }
}

async function editRule(row) {
  const cur = row.alertRuleJson || '{"temperature":{"pre":60,"warn":70,"severe":85}}'
  const json = window.prompt('告警规则 JSON（多级阈值/趋势）', cur)
  if (!json) {
    return
  }
  try {
    await deviceAlertRuleApi(row.deviceNo, json)
    ok('告警规则已更新', row.deviceNo)
    loadDevices()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '更新失败')
  }
}

async function loadLatest() {
  try {
    const r = await telemetryLatestApi(board.value.deviceNo, board.value.pointCode)
    latest.value = r.data || {}
    const q = await telemetryQueryApi({
      deviceNo: board.value.deviceNo, pointCode: board.value.pointCode, limit: 100,
    })
    series.value = q.data || []
    const s = await telemetrySuspectApi({ deviceNo: board.value.deviceNo, limit: 20 })
    suspects.value = s.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '查询失败')
  }
}

async function injectAnomaly() {
  try {
    const pts = []
    const base = Date.now()
    for (let i = 0; i < 30; i++) {
      pts.push({
        deviceNo: board.value.deviceNo,
        pointCode: board.value.pointCode,
        val: 50 + i * 1.4 + (Math.random() * 2 - 1),
        quality: 'GOOD',
        ts: new Date(base - (30 - i) * 60000).toISOString(),
      })
    }
    // 可疑数据点（过滤演示）
    pts.push({ deviceNo: board.value.deviceNo, pointCode: board.value.pointCode,
      val: -999, quality: 'SUSPECT', ts: new Date().toISOString() })
    await telemetryInjectApi({ points: pts, batchTag: 'DEMO-' + Date.now() })
    ok('异常曲线已注入', `${pts.length} 点（含 1 条可疑数据）`)
    loadLatest()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '注入失败')
  }
}

async function loadAlarms() {
  loading.value = true
  try {
    const r = await alarmListApi()
    alarms.value = r.data || []
  } finally {
    loading.value = false
  }
}

async function alarmConfirm(row) {
  try {
    await alarmConfirmApi(row.eventNo, { remark: '移动端确认' })
    ElMessage.success('已确认')
    loadAlarms()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '确认失败')
  }
}
async function alarmProcessing(row) {
  try {
    await alarmProcessingApi(row.eventNo, { remark: '处理中' })
    ElMessage.success('已转处理中')
    loadAlarms()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}
async function alarmClose(row) {
  try {
    await alarmCloseApi(row.eventNo, { remark: '已恢复关闭' })
    ElMessage.success('已关闭')
    loadAlarms()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '关闭失败')
  }
}
async function scanEscalate() {
  const r = await alarmScanEscalateApi()
  ElMessage.info(`超时升级 ${r.data?.escalated || 0} 条`)
  loadAlarms()
}

async function loadDist() {
  const r = await deviceStatusDistApi()
  dist.value = r.data || {}
}

function loadTab() {
  if (tab.value === 'access') loadDevices()
  if (tab.value === 'alarm') loadAlarms()
  if (tab.value === 'status') loadDist()
}

watch(tab, loadTab)
onMounted(loadTab)
</script>
