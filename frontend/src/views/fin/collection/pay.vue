<template>
  <div class="page collection-page">
    <!-- ================= 8.4.2 付款执行 ================= -->
    <el-form inline>
      <el-input v-model="outQuery.keyword" clearable placeholder="单号/供应商" style="width:150px" />
      <el-select v-model="outQuery.status" clearable placeholder="状态" style="width:130px;margin-left:8px">
        <el-option label="待过账" value="PENDING_POST" />
        <el-option label="已过账" value="POSTED" />
        <el-option label="已对账" value="RECONCILED" />
        <el-option label="已作废" value="VOID" />
      </el-select>
      <el-button type="primary" @click="loadOutDocs" style="margin-left:8px">查询</el-button>
      <el-button type="success" @click="outDialogVisible = true">录入付款单</el-button>
    </el-form>
    <el-table :data="outRows" border size="small">
      <el-table-column prop="docNo" label="付款单号" width="160" />
      <el-table-column prop="partyName" label="供应商" min-width="140" show-overflow-tooltip />
      <el-table-column prop="amount" label="金额" width="110" align="right" />
      <el-table-column prop="payMethod" label="方式" width="90" />
      <el-table-column prop="payDate" label="付款日期" width="110" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column prop="writeoffState" label="核销状态" width="100" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onDocDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'PENDING_POST'" link type="success" @click="onPost(row)">过账</el-button>
          <el-button v-if="row.status === 'PENDING_POST'" link type="danger" @click="onVoid(row)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>
        <AppPagination
      v-model:current-page="outQuery.current"
      v-model:page-size="outQuery.size"
      :total="outTotal"
      @current-change="loadOutDocs"
      style="margin-top:8px;justify-content:flex-end"
    />
    <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
      title="付款过账：借 应付账款 / 贷 银行存款（余额不足 L1 阻断 C-4.6-10）；超审批金额阻断 C-4.6-09；预付单（PREPAY）凭证借 1123。" />

    <el-dialog v-model="outDialogVisible" title="录入付款单" width="520">
      <el-form :model="outForm" label-width="90px">
        <el-form-item label="供应商 ID" required>
          <el-input v-model="outForm.partyId" placeholder="供应商主数据 ID" />
        </el-form-item>
        <el-form-item label="供应商名">
          <el-input v-model="outForm.partyName" />
        </el-form-item>
        <el-form-item label="关联申请">
          <el-input v-model="outForm.reqId" placeholder="付款申请 ID（可空；关联后受审批额度卡控）" />
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input-number v-model="outForm.amount" :min="0.01" :precision="2" :step="100" />
        </el-form-item>
        <el-form-item label="支付方式">
          <el-select v-model="outForm.payMethod">
            <el-option v-for="m in payMethods" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="银行账户" required>
          <el-input v-model="outForm.bankAccountId" placeholder="银行账户 ID" />
        </el-form-item>
        <el-form-item label="付款日期" required>
          <el-date-picker v-model="outForm.payDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="outDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitOutDoc">创建（待过账）</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（含核销明细，收款/付款两页共用） -->
    <CashDocDetailDrawer />
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 8.4.2 付款执行（拆分自原收付管理多 tab 页，design D1）。
 * 共享状态见 useCollection.js；详情抽屉见 _parts/CashDocDetailDrawer.vue。
 */
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { createCashDoc } from '@/api/fin/collection'
import { useCollection } from './useCollection'
import CashDocDetailDrawer from './_parts/CashDocDetailDrawer.vue'

const {
  payMethods, outQuery, outRows, outTotal, outDialogVisible, outForm,
  loadOutDocs, onPost, onVoid, onDocDetail
} = useCollection()

async function submitOutDoc() {
  const payload = { ...outForm.value, direction: 'OUT', currency: 'CNY' }
  const { data } = await createCashDoc(payload)
  ElMessage.success(`付款单 ${data.docNo} 已创建（待过账）`)
  outDialogVisible.value = false
  loadOutDocs()
}

onMounted(() => {
  loadOutDocs()
})
</script>
