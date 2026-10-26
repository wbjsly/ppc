<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">加班工资（10.4.3）</span>
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="wageRows" height="480">
        <el-table-column prop="confirmNo" label="确认单" width="160" />
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="otDate" label="加班日" width="110" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ { WEEKDAY: '平日 1.5x', WEEKEND: '周末 2x', HOLIDAY: '法定假 3x' }[row.otType] }}</template>
        </el-table-column>
        <el-table-column prop="validHours" label="有效时长" width="90" />
        <el-table-column prop="hourlyBase" label="小时基数" width="110">
          <template #default="{ row }">{{ row.hourlyBase ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="固化金额" width="120">
          <template #default="{ row }">
            <b v-if="row.wageAmount != null">¥{{ row.wageAmount }}</b>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="splitBy" label="分流人" width="110" />
        <el-table-column prop="splitAt" label="分流时间" width="160" />
      </el-table>
      <el-alert type="info" :closable="false" style="margin-top:12px"
                title="金额在分流确认时点固化：小时基数 = 月薪(BASE_SALARY)/21.75/8，改月薪不回溯历史（FR-4.7-6-7）；未来 10.5 薪酬核算直接取数不重算" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { otConfirmList } from '@/api/hr/attendance'

const wageRows = ref([])

async function load() {
  const { data } = await otConfirmList({ status: 'SPLITTED' })
  wageRows.value = (data || []).filter(r => r.splitChoice === 'WAGE')
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
.muted { color: #999; }
</style>
