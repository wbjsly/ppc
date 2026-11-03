<template>
  <div class="intf-page">
    <PageInfo title="服务报告（15.8）：接口 SLA 月度生成、归档发布" description="复用 interface-sla-monitoring。四指标（可用率/P95/错误率/同步延迟）月度采集；未归档不得对外发布（BR-5.5-16 / C-5.5-04）。" style="margin-bottom:12px" />

    <!-- 15.8.1 月度生成 -->
    <el-table :data="reports" border size="small">
      <el-table-column prop="period" label="月份" width="100" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="stTag(row.status)">{{ stText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dataAsOf" label="数据截止" width="160" />
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="generate(row)">月度生成</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
              title="月度采集四指标并生成报告快照；重复生成覆盖当期草稿" />
  </div>
</template>

<script setup>
/**
 * 15.8.1 月度生成（拆分自原服务报告操作台）：四指标报告快照生成。
 * 共享状态见 useSla.js，状态标签映射见 sla-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { generateReportApi } from '@/api/proc/interface-integration'
import { useSla } from './useSla'
import { stTag, stText } from './sla-helpers'

const { reports, load } = useSla()

onMounted(load)

async function generate(row) {
  await generateReportApi({ period: row.period })
  ElMessage.success('已生成月度 SLA 报告')
  load()
}
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
