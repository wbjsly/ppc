<template>
  <div class="page gl-page">
    <!-- ================= 8.1.3 凭证审核 ================= -->
    <el-form inline>
      <el-button type="primary" @click="loadManual('DRAFT')">刷新待审</el-button>
    </el-form>
    <el-table :data="auditRows" border size="small">
      <el-table-column prop="voucherNo" label="凭证号" width="160" />
      <el-table-column prop="voucherDate" label="日期" width="110" />
      <el-table-column prop="createBy" label="制单人" width="110" />
      <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip />
      <el-table-column prop="totalDr" label="金额" width="110" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row.id)">明细</el-button>
          <el-button size="small" type="success" :disabled="row.createBy === currentUser"
                     :title="row.createBy === currentUser ? '制单人与审核人不能为同一人（C-4.6-07）' : ''"
                     @click="doAudit(row.id)">审核</el-button>
          <el-button size="small" type="warning" @click="doReject(row.id)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 凭证详情/打印抽屉（录入/审核两页共用） -->
    <VoucherDetailDrawer />
  </div>
</template>

<script setup>
/**
 * 8.1.3 凭证审核（拆分自原总账多 tab 页，design D1）。
 * 待审列表/详情抽屉/审核动作见 useGl.js；详情抽屉见 _parts/VoucherDetailDrawer.vue。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { manualReject } from '@/api/fin/gl'
import { useGl } from './useGl'
import VoucherDetailDrawer from './_parts/VoucherDetailDrawer.vue'

const { auditRows, loadManual, openDetail, doAudit } = useGl()

const currentUser = ref('')   // 由登录态推导（C-4.6-07 前端置灰提示；后端硬卡控兜底）
try {
  const raw = localStorage.getItem('user')
  currentUser.value = raw ? (JSON.parse(raw).username || '') : ''
} catch (e) { currentUser.value = '' }

async function doReject(id) {
  const { value } = await ElMessageBox.prompt('驳回原因', '驳回')
  await manualReject(id, value)
  ElMessage.success('已驳回留痕')
  loadManual('DRAFT')
}

onMounted(() => {
  loadManual('DRAFT')
})
</script>
