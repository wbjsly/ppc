<template>
  <div style="padding:16px">
    <el-radio-group v-model="tab" size="small" style="margin-bottom:12px">
      <el-radio-button value="generate">月度报告</el-radio-button>
      <el-radio-button value="publish">归档发布</el-radio-button>
    </el-radio-group>

    <!-- 13.9.1 移动看板：五指标 + 生成 -->
    <template v-if="tab === 'generate'">
      <el-form inline size="small">
        <el-form-item label="报告期">
          <el-input v-model="period" placeholder="yyyy-MM" style="width:120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="generate">生成报告</el-button>
          <el-button @click="load">刷新</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="reports" size="small" v-loading="loading">
        <el-table-column prop="period" label="报告期" width="110" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success'
              : row.status === 'ARCHIVED' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="五指标快照" min-width="420">
          <template #default="{ row }">
            <template v-if="parse(row.metricsJson)">
              <el-tag v-for="(v, k) in parse(row.metricsJson)" :key="k" size="small"
                      style="margin-right:6px">
                {{ metricName(k) }}: {{ v }}
              </el-tag>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="publishAt" label="发布时间" width="160" />
      </el-table>
    </template>

    <!-- 13.9.2 归档发布 + 订阅 -->
    <template v-else>
      <el-form inline size="small">
        <el-form-item label="报告期">
          <el-input v-model="period" placeholder="yyyy-MM" style="width:120px" />
        </el-form-item>
        <el-form-item label="订阅人">
          <el-input v-model="subscriber" placeholder="订阅账号" style="width:160px" />
        </el-form-item>
        <el-form-item>
          <el-button @click="subscribe">订阅</el-button>
          <el-button type="warning" @click="archive">归档（推送订阅）</el-button>
          <el-button type="success" @click="publish">发布</el-button>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" style="margin-bottom:8px"
                title="C-05 未归档不得发布：DRAFT 直接发布返回 422 并留痕；归档自动推送订阅人（防重复）" />
      <el-table :data="reports" size="small">
        <el-table-column prop="period" label="报告期" width="110" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="publishLog" label="阻断留痕" show-overflow-tooltip />
      </el-table>
    </template>

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.9 服务报告两叶（add-iot-mobile）：移动看板（五指标月度报告生成）/
 * 归档发布（C-05 未归档阻断 + 订阅推送）。
 */
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  slaGenerateApi, slaArchiveApi, slaPublishApi, slaListApi, slaSubscribeApi,
} from '@/api/iot/todo'

const route = useRoute()
const tab = ref(route.meta.tab || 'generate')

const period = ref(new Date().toISOString().slice(0, 7))
const subscriber = ref('')
const reports = ref([])
const loading = ref(false)
const result = ref(null)

const METRIC_NAMES = {
  availabilityPct: '可用率%', syncSuccessPct: '同步成功率%',
  avgAlertRespMin: '响应min', offlineSharePct: '离线占比%',
  alarmClosePct: '告警闭环%',
}

function metricName(k) {
  return METRIC_NAMES[k] || k
}

function parse(json) {
  try {
    return JSON.parse(json)
  } catch (e) {
    return null
  }
}

async function load() {
  loading.value = true
  try {
    const r = await slaListApi()
    reports.value = r.data || []
  } finally {
    loading.value = false
  }
}

async function generate() {
  try {
    const r = await slaGenerateApi(period.value)
    result.value = { type: 'success', title: `报告已生成 ${r.data?.period}`,
      desc: String(r.data?.metricsJson || '') }
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '生成失败')
  }
}

async function archive() {
  try {
    const r = await slaArchiveApi(period.value)
    result.value = { type: 'success', title: `已归档（推送 ${r.data?.subscribersPushed || 0} 订阅人）`,
      desc: `状态 ${r.data?.status}` }
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '归档失败')
  }
}

async function publish() {
  try {
    const r = await slaPublishApi(period.value)
    result.value = { type: 'success', title: `已发布 ${r.data?.period}`,
      desc: `发布时间 ${r.data?.publishAt || ''}` }
    load()
  } catch (e) {
    // 422 未归档阻断（C-05）：展示留痕语义
    result.value = { type: 'warning', title: '发布被阻断（C-05 未归档）',
      desc: e?.response?.data?.message || e.message }
  }
}

async function subscribe() {
  if (!subscriber.value) {
    ElMessage.error('订阅人必填')
    return
  }
  try {
    await slaSubscribeApi(subscriber.value)
    ElMessage.success('订阅成功（归档自动推送）')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '订阅失败')
  }
}

function loadTab() {
  load()
}

watch(tab, loadTab)
onMounted(loadTab)
</script>
