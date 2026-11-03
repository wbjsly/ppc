<template>
  <div class="intf-page">
    <PageInfo title="沙箱联调（15.6）：沙箱配置、联调测试、生产放行" description="复用 interface-onboarding。沙箱通过率须达 100% 方可申请生产放行（BR-5.5-02）；放行双人会签（C-0-03）。" style="margin-bottom:12px" />

    <!-- 15.6.2 联调测试 -->
    <el-table :data="contracts" border size="small">
      <el-table-column prop="contractNo" label="契约号" width="150" />
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column prop="version" label="版本" width="90" />
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button size="small" @click="passRate(row)">联调通过率</el-button>
          <el-button size="small" type="primary" @click="simulate(row)">跑一次模拟</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-alert v-if="rate != null" :closable="false" style="margin-top:12px"
      :type="rate >= 100 ? 'success' : 'warning'"
      :title="`沙箱联调通过率：${rate}%（${rate >= 100 ? '可申请生产放行' : '未达 100%，不可放行'}）`" />
  </div>
</template>

<script setup>
/**
 * 15.6.2 联调测试（拆分自原沙箱联调操作台）：通过率查询 / 跑模拟。
 * 共享契约列表见 useSandbox.js；契约状态标签为本页独有，留页内。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getPassRateApi, runSimulationApi } from '@/api/proc/interface-integration'
import { useSandbox } from './useSandbox'

const { contracts, loadContracts } = useSandbox()

const rate = ref(null)

function stTag(s) {
  return { PUBLISHED: 'success', DRAFT: 'info', REVIEWING: 'warning' }[s] || 'info'
}

onMounted(loadContracts)

async function passRate(row) {
  const r = await getPassRateApi(row.id)
  rate.value = r.data?.data?.passRate ?? null
  if (rate.value == null) ElMessage.info('暂无联调数据')
}

async function simulate(row) {
  try {
    await runSimulationApi({ contractId: row.id })
    ElMessage.success('模拟已执行')
    loadContracts()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '模拟失败')
  }
}
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
