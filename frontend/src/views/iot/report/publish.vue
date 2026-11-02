<template>
  <div style="padding:16px">
    <!-- 13.9.2 归档发布 + 订阅 -->
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

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.9.2 报告订阅（拆分自原服务报告操作台）：订阅 + 归档推送 + 发布（C-05 未归档阻断）。
 * 报告期/列表/加载态/结果条见 useReport.js（与移动看板页共享）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { slaArchiveApi, slaPublishApi, slaSubscribeApi } from '@/api/iot/todo'
import { useReport } from './useReport'

const { period, reports, result, load } = useReport()

const subscriber = ref('')

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

onMounted(() => {
  load()
})
</script>
