<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>入职管理（10.7.1）—— 建档进度与入职提醒</span>
          <el-button type="primary" size="small" @click="loadAll">刷新</el-button>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        title="建档登记在 10.1.1；本页跟踪入职进度：建档状态 + 开通审批状态 + 试用期提醒" />

      <div class="warn-title">试用期 ≤15 天提醒（BR-4.7-08）</div>
      <el-table :data="remindings" size="small" border class="mb8">
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="hireDate" label="入职日期" width="110" />
        <el-table-column prop="probationEnd" label="试用期截止" width="110" />
        <el-table-column prop="daysLeft" label="剩余天数" width="90">
          <template #default="{ row }">
            <el-tag type="warning" size="small">{{ row.daysLeft }} 天</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="warn-title">建档进度</div>
      <el-table :data="onbs" size="small" border>
        <el-table-column prop="onbNo" label="建档单号" width="130" />
        <el-table-column prop="offerNo" label="录用单" width="130" />
        <el-table-column label="步骤进度" min-width="220">
          <template #default="{ row }">
            <el-steps :active="(row.step || 1) - 1" simple size="small">
              <el-step v-for="n in 6" :key="n" :title="n" />
            </el-steps>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DONE' ? 'success' : row.status === 'RETURNED' ? 'warning' : 'primary'" size="small">
              {{ { IN_PROGRESS: '推进中', RETURNED: '退回', DONE: '完成' }[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { onbList, probationRemindings } from '@/api/hr/employee-archive'

const onbs = ref([])
const remindings = ref([])

async function loadAll() {
  try { onbs.value = (await onbList({})).data || [] } catch (e) { /* ignore */ }
  try { remindings.value = (await probationRemindings()).data || [] } catch (e) { /* ignore */ }
}
onMounted(loadAll)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.warn-title { font-weight: 600; margin: 8px 0; }
</style>
