<template>
  <div class="i18n-page">
    <PageInfo title="服务报告（17.7）：月度报告、归档发布" description="五指标（版本数/三语覆盖率/缺口闭环率/渲染异常/灰度回退）；未归档发布 422 阻断留痕（BR-5.7-14 / C-5.7-05，复用 18.5 范式）。" style="margin-bottom:12px" />

    <!-- 17.7.2 归档发布 -->
    <el-form inline size="small">
      <el-form-item label="月份">
        <el-date-picker v-model="pubPeriod" type="month" value-format="YYYY-MM" style="width:140px" />
      </el-form-item>
      <el-button type="warning" size="small" @click="archive">归档</el-button>
      <el-button type="primary" size="small" @click="publish">对外发布</el-button>
    </el-form>
    <el-alert type="warning" :closable="false" style="margin-bottom:10px"
      title="流程：DRAFT → 归档（版本锁定）→ PUBLISHED；未归档发布 422 硬阻断并留痕（BR-5.7-14 / C-5.7-05）" />
    <el-table :data="slaList" border size="small">
      <el-table-column label="月份" width="140">
        <template #default="{ row }">{{ String(row.k || '').replace('I18N_SLA_STATUS_', '') }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="130">
        <template #default="{ row }">
          <el-tag size="small" :type="stTag(row.status)">{{ stText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 17.7.2 归档发布（拆分自原服务报告页）：SLA 报告归档 / 对外发布。
 * 列表共享状态见 useSlaReport.js；状态标签映射见 sla-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { archiveSlaApi, publishSlaApi } from '@/api/i18n/i18n'
import { stTag, stText } from './sla-helpers'
import { useSlaReport } from './useSlaReport'

const { defaultPeriod, slaList, loadList } = useSlaReport()

const pubPeriod = ref(defaultPeriod)

onMounted(loadList)

async function archive() {
  await ElMessageBox.confirm(`归档 ${pubPeriod.value} 报告？归档后版本锁定`, '提示', { type: 'warning' })
  try {
    await archiveSlaApi(pubPeriod.value)
    ElMessage.success('已归档')
    loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '归档失败')
  }
}
async function publish() {
  try {
    await publishSlaApi(pubPeriod.value, 'ALL')
    ElMessage.success('已发布')
    loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '发布被阻断（未归档 BR-5.7-14）')
  }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
