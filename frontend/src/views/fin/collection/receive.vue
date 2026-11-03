<template>
  <div class="page collection-page">
    <!-- ================= 8.4.1 收款执行 ================= -->
    <el-form inline>
      <el-input v-model="inQuery.keyword" clearable placeholder="单号/客户" style="width:150px" />
      <el-select v-model="inQuery.status" clearable placeholder="状态" style="width:130px;margin-left:8px">
        <el-option label="待过账" value="PENDING_POST" />
        <el-option label="已过账" value="POSTED" />
        <el-option label="已对账" value="RECONCILED" />
        <el-option label="已作废" value="VOID" />
      </el-select>
      <el-button type="primary" @click="loadInDocs" style="margin-left:8px">查询</el-button>
      <el-button type="success" @click="inDialogVisible = true">录入收款单</el-button>
    </el-form>
    <el-table :data="inRows" border size="small">
      <el-table-column prop="docNo" label="收款单号" width="160" />
      <el-table-column prop="partyName" label="客户" min-width="140" show-overflow-tooltip />
      <el-table-column prop="amount" label="金额" width="110" align="right" />
      <el-table-column prop="payMethod" label="方式" width="90" />
      <el-table-column prop="payDate" label="收款日期" width="110" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column prop="writeoffState" label="核销状态" width="100" />
      <el-table-column label="跨期" width="70" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.crossPeriod === '1'" type="warning" size="small">跨期</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onDocDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'PENDING_POST'" link type="success" @click="onPost(row)">过账</el-button>
          <el-button v-if="row.status === 'PENDING_POST'" link type="danger" @click="onVoid(row)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>
        <AppPagination
      v-model:current-page="inQuery.current"
      v-model:page-size="inQuery.size"
      :total="inTotal"
      @current-change="loadInDocs"
      style="margin-top:8px;justify-content:flex-end"
    />
    <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
      title="收款过账：借 银行存款 / 贷 应收账款（FIFO 核销合计）/ 贷 预收账款 2205（差额）；跨期核销自动挂财务主管审批（C-4.6-02）。" />

    <el-dialog v-model="inDialogVisible" title="录入收款单" width="520">
      <el-form :model="inForm" label-width="90px">
        <el-form-item label="客户 ID" required>
          <el-input v-model="inForm.partyId" placeholder="客户主数据 ID" />
        </el-form-item>
        <el-form-item label="客户名称">
          <el-input v-model="inForm.partyName" />
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input-number v-model="inForm.amount" :min="0.01" :precision="2" :step="100" />
        </el-form-item>
        <el-form-item label="支付方式">
          <el-select v-model="inForm.payMethod">
            <el-option v-for="m in payMethods" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="银行账户" required>
          <el-input v-model="inForm.bankAccountId" placeholder="银行账户 ID" />
        </el-form-item>
        <el-form-item label="收款日期" required>
          <el-date-picker v-model="inForm.payDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="inForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitInDoc">创建（待过账）</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（含核销明细，收款/付款两页共用） -->
    <CashDocDetailDrawer />
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 8.4.1 收款执行（拆分自原收付管理多 tab 页，design D1）。
 * 共享状态见 useCollection.js；详情抽屉见 _parts/CashDocDetailDrawer.vue。
 */
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { createCashDoc } from '@/api/fin/collection'
import { useCollection } from './useCollection'
import CashDocDetailDrawer from './_parts/CashDocDetailDrawer.vue'

const {
  payMethods, inQuery, inRows, inTotal, inDialogVisible, inForm,
  loadInDocs, onPost, onVoid, onDocDetail
} = useCollection()

async function submitInDoc() {
  const { data } = await createCashDoc({ ...inForm.value, direction: 'IN', currency: 'CNY' })
  ElMessage.success(`收款单 ${data.docNo} 已创建（待过账）`)
  inDialogVisible.value = false
  loadInDocs()
}

onMounted(() => {
  loadInDocs()
})
</script>
