<template>
  <div class="page ap-mgmt-page">
    <!-- ================= 8.3.1 应付确认（三方匹配财务审核） ================= -->
    <el-form inline>
      <el-input v-model="mtQuery.supplierId" clearable placeholder="供应商 ID" style="width:160px" />
      <el-input v-model="mtQuery.status" clearable placeholder="匹配状态" style="width:130px;margin-left:8px" />
      <el-button type="primary" @click="loadMatches" style="margin-left:8px">查询</el-button>
    </el-form>
    <el-table :data="mtRows" border size="small">
      <el-table-column prop="invoiceNo" label="发票号" width="150" show-overflow-tooltip />
      <el-table-column prop="supplierName" label="供应商" min-width="150" show-overflow-tooltip />
      <el-table-column prop="poNo" label="PO 号" width="140" show-overflow-tooltip />
      <el-table-column prop="status" label="匹配状态" width="110" />
      <el-table-column prop="diffRate" label="差异率" width="90" align="right" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onMatchDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'EXCEPTION'" link type="warning" @click="onConfirmMatch(row)">确认价差</el-button>
          <el-button v-if="row.status === 'EXCEPTION'" link type="danger" @click="onManualPost(row)">跨期过账</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="mtQuery.current" v-model:page-size="mtQuery.size"
      :total="mtTotal" layout="total, prev, pager, next" @change="loadMatches"
      style="margin-top:8px;justify-content:flex-end" />
    <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
      title="财务确认视角：价差超容差须确认意见后过账；跨会计期间须 ADMIN 手工过账。确认后发票转正式应付并自动冲回暂估。" />
    <el-dialog v-model="cfOpinionVisible" title="确认价差（必填意见）" width="480">
      <el-input v-model="cfOpinion" type="textarea" :rows="3" placeholder="价差原因与确认意见" />
      <template #footer>
        <el-button @click="cfOpinionVisible = false">取消</el-button>
        <el-button type="primary" @click="submitConfirmMatch">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 8.3.1 应付确认（拆分自原应付管理多 tab 页，design D1）。
 * 本页独有逻辑，无跨页共享状态（D2：无共享未抽）。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  matchPage, matchDetail, confirmMatch, manualPostMatch
} from '@/api/fin/ap'

const mtQuery = reactive({ current: 1, size: 20, supplierId: '', status: '' })
const mtRows = ref([])
const mtTotal = ref(0)
const cfOpinionVisible = ref(false)
const cfOpinion = ref('')
let cfCurrentMatch = null

async function loadMatches() {
  const { data } = await matchPage({
    ...mtQuery, supplierId: mtQuery.supplierId || undefined, status: mtQuery.status || undefined
  })
  mtRows.value = data.records || []
  mtTotal.value = Number(data.total || 0)
}

async function onMatchDetail(row) {
  const { data } = await matchDetail(row.id)
  ElMessageBox.alert(JSON.stringify(data, null, 2), '匹配单详情', { customStyle: { width: '60%' } })
}

function onConfirmMatch(row) {
  cfCurrentMatch = row
  cfOpinion.value = ''
  cfOpinionVisible.value = true
}

async function submitConfirmMatch() {
  if (!cfOpinion.value.trim()) {
    ElMessage.warning('确认意见必填')
    return
  }
  await confirmMatch(cfCurrentMatch.id, cfOpinion.value.trim())
  ElMessage.success('价差已确认')
  cfOpinionVisible.value = false
  loadMatches()
}

async function onManualPost(row) {
  await ElMessageBox.confirm('跨期手工过账（仅 ADMIN）：生成当期价差调整凭证并注明跨期原因。继续？', '跨期过账', { type: 'warning' })
  await manualPostMatch(row.id, '跨期手工过账')
  ElMessage.success('已过账')
  loadMatches()
}

onMounted(() => {
  loadMatches()
})
</script>
