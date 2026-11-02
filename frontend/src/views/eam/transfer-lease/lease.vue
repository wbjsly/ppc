<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      title="租赁管理（7.4.3）：合同 → 分类确认 → 使用权确认（PV+摊余计划+ROU 建卡）→ 月度处理（幂等）→ 重计量 / 临期 / 到期终止"
      description="短期低值豁免（BR-25）不建卡不出计划，直线进损益留痕；重新计量经财务资产会计单签。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openLeaseDialog()">新建合同</el-button>
        <el-date-picker v-model="periodTag" type="month" value-format="YYYYMM" placeholder="会计月"
          style="width:130px;margin-right:8px;" />
        <el-button type="success" @click="doMonthly">月度处理</el-button>
        <el-button type="warning" plain @click="doReverse">冲回</el-button>
        <el-button @click="loadExpiry">临期清单</el-button>
        <span class="spacer" />
        <el-button plain size="small" @click="loadLeases">刷新</el-button>
      </div>

      <el-table :data="leaseRows" size="small" border v-loading="loading" max-height="360">
        <el-table-column prop="lseNo" label="合同号" width="170" />
        <el-table-column prop="lessor" label="出租方" min-width="130" />
        <el-table-column prop="assetDesc" label="租赁资产" min-width="150" />
        <el-table-column prop="termsMonths" label="租期(月)" width="80" align="right" />
        <el-table-column prop="paymentAmount" label="每期租金" width="100" align="right" />
        <el-table-column label="付款周期" width="80" align="center">
          <template #default="{ row }">{{ cycleLabel(row.payCycleMonths) }}</template>
        </el-table-column>
        <el-table-column prop="classify" label="分类" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.classify" size="small">{{ classifyLabel(row.classify) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="pv" label="PV" width="110" align="right" />
        <el-table-column prop="rouAmount" label="使用权资产" width="110" align="right" />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="leaseTag(row.status)" size="small">{{ leaseLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small"
              @click="doClassify(row)">分类</el-button>
            <el-button v-if="row.status === 'CLASSIFIED'" link type="success" size="small"
              @click="doConfirm(row)">使用权确认</el-button>
            <el-button v-if="row.status === 'CONFIRMED'" link type="warning" size="small"
              @click="viewSchedule(row)">摊余计划</el-button>
            <el-button v-if="row.status === 'CONFIRMED'" link type="primary" size="small"
              @click="openRemeasure(row)">变更重计量</el-button>
            <el-button v-if="row.status === 'CONFIRMED'" link type="danger" size="small"
              @click="doTerminate(row)">到期终止</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建合同对话框 -->
    <el-dialog v-model="leaseDlg" title="租赁合同录入" width="640px">
      <el-form :model="leaseForm" label-width="120px">
        <el-form-item label="出租方" required><el-input v-model="leaseForm.lessor" /></el-form-item>
        <el-form-item label="租赁资产" required><el-input v-model="leaseForm.assetDesc" /></el-form-item>
        <el-form-item label="租赁期起" required>
          <el-date-picker v-model="leaseForm.startDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="租赁期止" required>
          <el-date-picker v-model="leaseForm.endDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="每期租金" required><el-input-number v-model="leaseForm.paymentAmount" :min="0.01" :precision="2" /></el-form-item>
        <el-form-item label="付款周期">
          <el-select v-model="leaseForm.payCycleMonths" style="width:140px;">
            <el-option :value="1" label="月付" /><el-option :value="3" label="季付" />
            <el-option :value="6" label="半年付" /><el-option :value="12" label="年付" />
          </el-select>
        </el-form-item>
        <el-form-item label="折现率(%)">
          <el-input-number v-model="leaseForm.discountRatePct" :min="0" :precision="4" />
          <span class="hint">留空取 SysParam LEASE_DEFAULT_DISCOUNT_RATE</span>
        </el-form-item>
        <el-form-item label="取值依据">
          <el-radio-group v-model="leaseForm.rateBasis">
            <el-radio value="INCLUSIVE">租赁内含利率</el-radio>
            <el-radio value="INCREMENTAL">增量借款利率</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="购买选择权">
          <el-radio-group v-model="leaseForm.buyOption">
            <el-radio value="0">无</el-radio><el-radio value="1">有</el-radio>
          </el-radio-group>
          <el-input-number v-if="leaseForm.buyOption === '1'" v-model="leaseForm.buyPrice"
            :min="0" :precision="2" placeholder="约定购买价" style="margin-left:8px;" />
        </el-form-item>
        <el-form-item label="初始直接费用"><el-input-number v-model="leaseForm.initDirectCost" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="预付租金"><el-input-number v-model="leaseForm.prepaidRent" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="租赁激励"><el-input-number v-model="leaseForm.leaseIncentive" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="豁免理由">
          <el-input v-model="leaseForm.exemptReason" placeholder="分类为短期低值豁免时必填（BR-25 L5 留痕）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="leaseDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreateLease">保存（条款完整性校验）</el-button>
      </template>
    </el-dialog>

    <!-- 摊余计划查看 -->
    <el-dialog v-model="schedDlg" :title="`摊余成本计划（${schedLseNo}）`" width="860px">
      <el-table :data="schedRows" size="small" border max-height="420">
        <el-table-column prop="seq" label="月序" width="60" align="center" />
        <el-table-column prop="periodTag" label="会计月" width="90" align="center" />
        <el-table-column prop="beginBal" label="期初负债" width="120" align="right" />
        <el-table-column prop="interest" label="利息" width="110" align="right" />
        <el-table-column prop="payment" label="付款" width="110" align="right" />
        <el-table-column prop="endBal" label="期末负债" width="120" align="right" />
        <el-table-column label="已处理" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.processedFlag === '1'" type="success" size="small">已处理</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 临期清单 -->
    <el-dialog v-model="expiryDlg" title="租赁临期清单（6 月/1 月分级）" width="820px">
      <el-table :data="expiryRows" size="small" border max-height="380">
        <el-table-column prop="lseNo" label="合同号" width="170" />
        <el-table-column prop="lessor" label="出租方" min-width="120" />
        <el-table-column prop="assetDesc" label="租赁资产" min-width="140" />
        <el-table-column prop="endDate" label="到期日" width="110" align="center" />
        <el-table-column prop="monthsLeft" label="剩(月)" width="80" align="right" />
        <el-table-column label="分级" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.level === 'M1' ? 'danger' : 'warning'" size="small">
              {{ row.level === 'M1' ? '剩1月·暂估' : '剩6月' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 7.4.3 租赁管理（拆分自原调拨租赁操作台，原为页内 v-else 隐藏 tab）：
 * 合同录入、分类与使用权确认、月度处理/冲回、摊余计划、临期与到期终止。
 * 共享状态见 useTransferLease.js。
 */
import { onMounted } from 'vue'
import {
  loading, saving, leaseRows, leaseDlg, leaseForm, periodTag,
  schedDlg, schedRows, schedLseNo, expiryDlg, expiryRows,
  loadLeases, openLeaseDialog, doCreateLease, doClassify, doConfirm,
  viewSchedule, doMonthly, doReverse, openRemeasure, loadExpiry, doTerminate,
  leaseLabel, leaseTag, classifyLabel, cycleLabel
} from './useTransferLease'

onMounted(() => loadLeases())
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; align-items: center; gap: 4px; margin-bottom: 8px; }
.spacer { flex: 1; }
.hint { margin-left: 8px; color: #909399; font-size: 12px; }
</style>
