<template>
  <div style="padding:16px">
    <!-- 13.6.1 设备接入：注册 + 连接测试 + 规则配置 -->
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

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.6.1 设备接入（拆分自原设备监控操作台）：设备注册 + 连接测试 + 告警规则配置。
 * 加载态与结果条见 useMonitoring.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  deviceRegisterApi, deviceConnectTestApi, deviceListApi, deviceAlertRuleApi,
} from '@/api/iot/monitor'
import { useMonitoring } from './useMonitoring'

const { loading, result, ok } = useMonitoring()

const devices = ref([])
const reg = ref({ deviceNo: '', protocol: 'MQTT', sampleFreqSec: 60 })

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

onMounted(() => {
  loadDevices()
})
</script>
