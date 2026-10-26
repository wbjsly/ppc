<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">调休额度（10.4.2）</span>
        <div>
          <span style="margin-right:12px">可用合计：<b>{{ balance.totalHours || 0 }}</b> 小时（FEFO 序）</span>
          <el-button type="warning" @click="expiryScanNow">到期扫描（提醒+清零）</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="batches" height="420">
        <el-table-column prop="batchNo" label="批次号" width="160" />
        <el-table-column prop="empId" label="员工" width="200" />
        <el-table-column prop="hours" label="生成" width="80" />
        <el-table-column prop="remain" label="剩余" width="80" />
        <el-table-column prop="generatedAt" label="生成时间" width="160" />
        <el-table-column prop="expiredAt" label="有效期至" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="{ ACTIVE: 'success', USED: 'info', EXPIRED: 'danger' }[row.status]">
              {{ { ACTIVE: '有效', USED: '已用', EXPIRED: '已清零' }[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="到期预警" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'ACTIVE' && daysLeft(row.expiredAt) <= 15" type="warning" size="small">
              {{ daysLeft(row.expiredAt) }} 天后到期
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { compTimeBatches, compTimeBalance, compTimeExpiryScan } from '@/api/hr/attendance'

const batches = ref([])
const balance = ref({})

const daysLeft = d => Math.ceil((new Date(d) - new Date()) / 86400000)

async function load() {
  const [b, bal] = await Promise.all([compTimeBatches({}), compTimeBalance({})])
  batches.value = b.data || []
  balance.value = bal.data || {}
}
async function expiryScanNow() {
  const out = await compTimeExpiryScan()
  ElMessage.success(`到期扫描：提醒 ${out.data?.reminded} 条，清零 ${out.data?.expired} 批（留痕）`)
  load()
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
