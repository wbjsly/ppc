<template>
  <div class="pay-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
      title="付款管理（2.7.3）：对账冻结 → 申请 → 分级审批 → 排期 → 执行 → 付款凭证 → FIFO 核销"
      description="C-4.2-15 对账差异超 0.5% 冻结付款须采购员+财务双签；付款按金额三级审批（≤5万 PM / ≤50万 +财务主管 / >50万 +总经理）；执行付款与排期仅 ADMIN；预付款受 C-4.2-13 双 L1 约束，发票过账后自动按最早未核销顺序冲抵应付。" />

    <el-tabs v-model="tab" @tab-change="onTab">

      <!-- ============ Tab1 对账单 ============ -->
      <el-tab-pane label="对账单" name="statement">
        <div class="toolbar">
          <el-button type="primary" @click="openStatement">登记对账单</el-button>
          <el-select v-model="stmtFilters.status" placeholder="状态" clearable style="width: 150px;" @change="loadStatements">
            <el-option label="容差内(ACCEPTED)" value="ACCEPTED" />
            <el-option label="差异冻结(EXCEPTION)" value="EXCEPTION" />
            <el-option label="双签关闭(CLOSED)" value="CLOSED" />
          </el-select>
          <el-select v-model="stmtFilters.supplierId" placeholder="供应商" clearable filterable
            style="width: 200px;" @change="loadStatements">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
          <el-button @click="loadStatements">查询</el-button>
        </div>
        <el-table :data="stmtRows" size="small" border v-loading="loading">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div style="padding: 8px 16px; font-size: 12px; color: #606266;">
                比对基准（OPEN 暂估余额）<b>{{ row.baseAmount }}</b> ·
                对账金额 <b>{{ row.stmtAmount }}</b> ·
                差异 <b :style="{ color: row.frozen ? '#F56C6C' : '#67C23A' }">{{ row.diffAmount }}</b>
                （差异率 {{ row.diffRate }}，容差 {{ row.tolerance }}）
                <div v-if="row.status === 'CLOSED'" style="margin-top: 6px;">
                  双签记录：采购员 {{ row.pmConfirmBy }}（{{ row.pmOpinion }}）·
                  财务 {{ row.finConfirmBy }}（{{ row.finOpinion }}）
                </div>
                <div v-else-if="row.remark" style="margin-top: 6px;">备注：{{ row.remark }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="stmtNo" label="对账单号" width="150">
            <template #default="{ row }"><b>{{ row.stmtNo }}</b></template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
          <el-table-column prop="stmtDate" label="对账日期" width="110" />
          <el-table-column prop="stmtAmount" label="对账金额" width="110" align="right" />
          <el-table-column prop="baseAmount" label="暂估余额" width="110" align="right" />
          <el-table-column label="差异" width="110" align="right">
            <template #default="{ row }">
              <span :style="{ color: row.frozen ? '#F56C6C' : '#67C23A' }">{{ row.diffAmount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="diffRate" label="差异率" width="100" align="right" />
          <el-table-column label="状态" width="120" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="stmtTag(row.status)">{{ stmtText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canOperate && row.status === 'EXCEPTION'" link type="warning"
                size="small" @click="doConfirmStatement(row)">双签确认</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="stmtTotal"
          v-model:current-page="stmtFilters.current" :page-size="stmtFilters.size"
          @current-change="loadStatements" />
      </el-tab-pane>

      <!-- ============ Tab2 付款申请 ============ -->
      <el-tab-pane label="付款申请" name="request">
        <div class="toolbar">
          <el-button type="primary" @click="openCreateRequest">创建付款申请</el-button>
          <el-select v-model="reqFilters.status" placeholder="状态" clearable style="width: 150px;" @change="loadRequests">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="审批中" value="PENDING_APPROVE" />
            <el-option label="已审批" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="已付款" value="PAID" />
          </el-select>
          <el-input v-model="reqFilters.keyword" placeholder="申请单号" clearable style="width: 170px;"
            @keyup.enter="loadRequests" @clear="loadRequests" />
          <el-button @click="loadRequests">查询</el-button>
        </div>
        <el-table :data="reqRows" size="small" border v-loading="loading" @expand-change="loadReqDetail">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div style="padding: 8px 16px;" v-loading="row._loading">
                <div v-if="row._detail">
                  <h4 class="sec">关联发票</h4>
                  <el-table :data="row._detail.invoices || []" size="mini" border>
                    <el-table-column prop="invoiceNo" label="发票号" width="170" />
                    <el-table-column prop="invoiceDate" label="开票日" width="110" />
                    <el-table-column prop="totalAmount" label="金额" width="110" align="right" />
                    <el-table-column prop="paidAmount" label="已核销" width="110" align="right" />
                    <el-table-column prop="unpaid" label="未清" width="110" align="right" />
                  </el-table>
                  <h4 class="sec" style="margin-top: 10px;">审批节点</h4>
                  <el-table :data="row._detail.approvalNodes || []" size="mini" border>
                    <el-table-column prop="seq" label="序" width="50" />
                    <el-table-column prop="nodeName" label="节点" width="140" />
                    <el-table-column prop="roleRequired" label="角色" width="170" />
                    <el-table-column prop="status" label="状态" width="100" />
                    <el-table-column prop="signerName" label="签署人" width="110" />
                    <el-table-column prop="opinion" label="意见" min-width="140" show-overflow-tooltip />
                  </el-table>
                  <h4 class="sec" style="margin-top: 10px;">付款记录</h4>
                  <el-table :data="row._detail.payments || []" size="mini" border>
                    <el-table-column prop="payNo" label="付款单" width="160" />
                    <el-table-column prop="applyAmount" label="金额" width="100" align="right" />
                    <el-table-column prop="deductAmount" label="抵扣" width="90" align="right" />
                    <el-table-column prop="actualAmount" label="实付" width="100" align="right" />
                    <el-table-column prop="payMethod" label="方式" width="100" />
                    <el-table-column prop="accountName" label="账户" min-width="150" show-overflow-tooltip />
                    <el-table-column prop="payDate" label="付款日" width="110" />
                  </el-table>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="reqNo" label="申请单号" width="160">
            <template #default="{ row }"><b style="color:#409EFF;">{{ row.reqNo }}</b></template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
          <el-table-column prop="applyAmount" label="申请金额" width="110" align="right" />
          <el-table-column prop="executedAmount" label="已执行" width="100" align="right" />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="reqTag(row.status)">{{ reqText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="planDate" label="计划付款日" width="115" />
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canOperate && (row.status === 'DRAFT' || row.status === 'REJECTED')"
                link type="primary" size="small" @click="doSubmit(row)">提交审批</el-button>
              <el-button v-if="canOperate && (row.status === 'DRAFT' || row.status === 'REJECTED')"
                link type="danger" size="small" @click="doCancel(row)">作废</el-button>
              <el-button v-if="canOperate && row.status === 'REJECTED'" link type="warning"
                size="small" @click="doResubmit(row)">改后重提</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="reqTotal"
          v-model:current-page="reqFilters.current" :page-size="reqFilters.size"
          @current-change="loadRequests" />
      </el-tab-pane>

      <!-- ============ Tab3 排期与执行 ============ -->
      <el-tab-pane label="排期与执行" name="execute">
        <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
          title="排期与执行付款仅 ADMIN（系统无出纳角色，ADMIN 代）：超审批金额 L1 阻断、账户余额不足 422" />
        <div class="toolbar">
          <el-select v-model="execFilter" placeholder="状态" clearable style="width: 160px;" @change="loadExec">
            <el-option label="待排期(APPROVED)" value="APPROVED" />
            <el-option label="已排期(SCHEDULED)" value="SCHEDULED" />
            <el-option label="待付款(WAIT_FUNDS)" value="WAIT_FUNDS" />
            <el-option label="已付款(PAID)" value="PAID" />
          </el-select>
          <el-button @click="loadExec">查询</el-button>
        </div>
        <el-table :data="execRows" size="small" border v-loading="loading">
          <el-table-column prop="reqNo" label="申请单号" width="160">
            <template #default="{ row }"><b>{{ row.reqNo }}</b></template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
          <el-table-column prop="applyAmount" label="审批金额" width="110" align="right" />
          <el-table-column prop="executedAmount" label="已执行" width="100" align="right" />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="reqTag(row.status)">{{ reqText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="planDate" label="计划付款日" width="115" />
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <template v-if="isAdmin">
                <el-button v-if="row.status === 'APPROVED'" link type="primary" size="small"
                  @click="openSchedule(row)">排期</el-button>
                <template v-if="row.status === 'SCHEDULED' || row.status === 'WAIT_FUNDS'">
                  <el-button link type="warning" size="small" @click="doWaitFunds(row)">
                    {{ row.status === 'SCHEDULED' ? '标待付款' : '恢复排期' }}
                  </el-button>
                  <el-button v-if="row.status === 'SCHEDULED'" link type="success" size="small"
                    @click="openExecute(row)">执行付款</el-button>
                </template>
              </template>
              <span v-else style="color:#909399; font-size:12px;">排期与执行仅 ADMIN</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ Tab4 核销记录 ============ -->
      <el-tab-pane label="核销记录" name="writeoff">
        <div class="toolbar">
          <el-select v-model="woFilters.kind" placeholder="类型" clearable style="width: 170px;" @change="loadWriteoffs">
            <el-option label="付款核销(PAYMENT)" value="PAYMENT" />
            <el-option label="预付冲抵(SETTLE)" value="SETTLE" />
          </el-select>
          <el-input v-model="woFilters.invoiceNo" placeholder="发票号" clearable style="width: 170px;"
            @keyup.enter="loadWriteoffs" @clear="loadWriteoffs" />
          <el-button @click="loadWriteoffs">查询</el-button>
        </div>
        <el-table :data="woRows" size="small" border v-loading="loading">
          <el-table-column label="类型" width="130" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.kind === 'PAYMENT' ? 'primary' : 'warning'">
                {{ row.kind === 'PAYMENT' ? '付款核销' : '预付冲抵' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="invoiceNo" label="发票号" width="170" />
          <el-table-column prop="poNo" label="PO" width="150" show-overflow-tooltip />
          <el-table-column prop="amount" label="核销金额" width="120" align="right" />
          <el-table-column prop="writeoffDate" label="核销日期" width="110" />
          <el-table-column prop="paymentId" label="付款单 ID" min-width="170" show-overflow-tooltip />
          <el-table-column prop="createDate" label="记录时间" width="165" />
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="woTotal"
          v-model:current-page="woFilters.current" :page-size="woFilters.size"
          @current-change="loadWriteoffs" />
      </el-tab-pane>

      <!-- ============ Tab5 预付款 ============ -->
      <el-tab-pane label="预付款" name="prepayment">
        <el-alert v-if="cleanupTodos.length" type="error" :closable="false" style="margin-bottom: 10px;"
          :title="`预付款清理待办 ${cleanupTodos.length} 条（供应商冻结且存在未核销预付，C-4.2-13/C-4.2-02）`"
          :description="cleanupTodos.map(t => `${t.ppNo} ${t.supplierName}(${t.supplierStatus}) 未核销 ${t.unsettled}`).join('；')" />
        <div class="toolbar">
          <el-button type="primary" @click="openCreatePrepay">创建预付款</el-button>
          <el-input v-model="settlePoNo" placeholder="按 PO 手动冲抵（兜底）" style="width: 200px;" clearable />
          <el-button @click="doSettle">立即冲抵</el-button>
          <el-button @click="loadPrepayments">刷新</el-button>
        </div>
        <el-table :data="ppRows" size="small" border v-loading="loading" @expand-change="loadPpDetail">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div style="padding: 8px 16px;" v-loading="row._loading">
                <div v-if="row._detail">
                  <h4 class="sec">冲抵记录（最早未核销顺序）</h4>
                  <el-table :data="row._detail.settles || []" size="mini" border>
                    <el-table-column prop="invoiceNo" label="发票号" width="180" />
                    <el-table-column prop="amount" label="冲抵金额" width="120" align="right" />
                    <el-table-column prop="writeoffDate" label="日期" width="120" />
                  </el-table>
                  <div v-if="!(row._detail.settles || []).length" style="color:#909399; font-size:12px;">
                    暂无冲抵（发票过账后系统自动冲抵，或点「立即冲抵」）
                  </div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="ppNo" label="预付单号" width="160">
            <template #default="{ row }"><b style="color:#E6A23C;">{{ row.ppNo }}</b></template>
          </el-table-column>
          <el-table-column prop="poNo" label="PO" width="150" show-overflow-tooltip />
          <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
          <el-table-column prop="prepayRatio" label="比例" width="70" align="center" />
          <el-table-column prop="applyAmount" label="申请金额" width="110" align="right" />
          <el-table-column prop="executedAmount" label="已付" width="100" align="right" />
          <el-table-column prop="settledAmount" label="已冲抵" width="100" align="right" />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="reqTag(row.status)">{{ reqText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="260" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canOperate && (row.status === 'DRAFT' || row.status === 'REJECTED')"
                link type="primary" size="small" @click="submitPrepay(row)">提交审批</el-button>
              <el-button v-if="canOperate && row.status !== 'PENDING_APPROVE' && row.status !== 'PAID'"
                link type="danger" size="small" @click="cancelPrepay(row)">作废</el-button>
              <el-button v-if="isAdmin && row.status === 'APPROVED'" link type="success" size="small"
                @click="openPrepayExecute(row)">执行预付</el-button>
              <el-button v-if="canOperate && row.status === 'PAID'" link type="warning" size="small"
                @click="settlePrepay(row)">立即冲抵</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="ppTotal"
          v-model:current-page="ppFilters.current" :page-size="ppFilters.size"
          @current-change="loadPrepayments" />
      </el-tab-pane>
    </el-tabs>

    <!-- 登记对账单 -->
    <el-dialog v-model="stmtVisible" title="登记供应商对账单" width="480px">
      <el-form label-width="100px">
        <el-form-item label="供应商" required>
          <el-select v-model="stmtForm.supplierId" filterable placeholder="选择供应商" style="width: 100%;"
            @change="v => stmtForm.supplierName = (suppliers.find(s => s.id === v) || {}).supplierName">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="对账日期" required>
          <el-date-picker v-model="stmtForm.stmtDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择日期" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="对账金额" required>
          <el-input-number v-model="stmtForm.stmtAmount" :min="0" :precision="2" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="stmtForm.remark" placeholder="选填（如供应商对账单编号）" />
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false"
        title="登记后系统自动与该供应商 OPEN 暂估余额比对：差异率 > 0.5% 即冻结付款并生成差异对账单（C-4.2-15，须双签）" />
      <template #footer>
        <el-button @click="stmtVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitStatement">登记</el-button>
      </template>
    </el-dialog>

    <!-- 创建付款申请 -->
    <el-dialog v-model="reqVisible" title="创建付款申请" width="720px">
      <el-form label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="供应商" required>
              <el-select v-model="reqForm.supplierId" filterable placeholder="选择供应商" style="width: 100%;"
                @change="onReqSupplierChange">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="申请金额" required>
              <el-input-number v-model="reqForm.applyAmount" :min="0" :precision="2" style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="关联发票" required>
          <div style="width: 100%;">
            <el-checkbox-group v-model="reqForm.invoiceIds">
              <div v-for="inv in reqInvoices" :key="inv.id" style="margin: 2px 0;">
                <el-checkbox :label="inv.id">
                  {{ inv.invoiceNo }}（{{ inv.invoiceDate }}）金额 {{ inv.totalAmount }} 未清 <b>{{ inv.unpaid }}</b>
                </el-checkbox>
              </div>
            </el-checkbox-group>
            <div style="font-size: 12px; color: #909399;">
              已选未清合计：<b>{{ reqSelectedUnpaid }}</b>
              <span v-if="Number(reqForm.applyAmount) > Number(reqSelectedUnpaid)" style="color:#F56C6C;">
                （申请金额超出未清合计将被阻断，FR-4.6-3-7）</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="reqForm.remark" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reqVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreateRequest">创建</el-button>
      </template>
    </el-dialog>

    <!-- 排期 -->
    <el-dialog v-model="schedVisible" title="付款排期" width="420px">
      <el-form label-width="110px">
        <el-form-item label="申请单号">
          <el-input :model-value="current && current.reqNo" readonly />
        </el-form-item>
        <el-form-item label="计划付款日" required>
          <el-date-picker v-model="schedForm.planDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择日期" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="schedVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitSchedule">确认排期</el-button>
      </template>
    </el-dialog>

    <!-- 执行付款 -->
    <el-dialog v-model="execVisible" title="执行付款（仅 ADMIN）" width="620px">
      <el-form label-width="110px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="申请单号">
              <el-input :model-value="current && current.reqNo" readonly />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="审批金额">
              <el-input :model-value="current && current.applyAmount" readonly />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="支付方式" required>
              <el-select v-model="execForm.payMethod" style="width: 100%;">
                <el-option v-for="m in payMethods" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="银行账户" required>
              <el-select v-model="execForm.bankAccountId" style="width: 100%;">
                <el-option v-for="b in banks" :key="b.id"
                  :label="`${b.accountName}（余额 ${b.balance}）`" :value="b.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="付款日期" required>
              <el-date-picker v-model="execForm.payDate" type="date" value-format="YYYY-MM-DD"
                placeholder="选择日期" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="付款金额" required>
              <el-input-number v-model="execForm.applyAmount" :min="0" :precision="2"
                style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="实付（试算）">
              <el-input :model-value="execActual" readonly />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="SCAR 抵扣">
          <div v-if="deductList.length" style="width: 100%;">
            <el-checkbox-group v-model="execForm.deductIds">
              <div v-for="d in deductList" :key="d.id" style="margin: 2px 0;">
                <el-checkbox :label="d.id">{{ d.deductNo }}（{{ d.scarNo }}）金额 {{ d.amount }}</el-checkbox>
              </div>
            </el-checkbox-group>
            <div style="font-size:12px; color:#909399;">抵扣合计 {{ execDeductSum }}（实付 = 付款金额 − 抵扣，抵扣部分贷记采购价差）</div>
          </div>
          <span v-else style="color:#909399; font-size:12px;">该供应商无待抵扣扣款单</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="execVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitExecute">确认付款</el-button>
      </template>
    </el-dialog>

    <!-- 创建预付款 -->
    <el-dialog v-model="ppVisible" title="创建预付款申请" width="560px">
      <el-form label-width="100px">
        <el-form-item label="PO" required>
          <el-select v-model="ppForm.poId" filterable placeholder="选择已批准 PO" style="width: 100%;"
            @change="v => ppPoChange(v)">
            <el-option v-for="p in poCandidates" :key="p.id"
              :label="`${p.poNo}（${p.supplierName}）总额 ${p.totalAmt}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请金额" required>
          <el-input-number v-model="ppForm.applyAmount" :min="0" :precision="2" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ppForm.remark" placeholder="选填（如合同先款后货条款）" />
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false"
        title="双 L1 校验（C-4.2-13）：金额 ≤ PO 总额 × 预付比例，且累计预付 ≤ PO 未清金额——比例取 PO 约定值，未维护按默认 30%" />
      <template #footer>
        <el-button @click="ppVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreatePrepay">创建</el-button>
      </template>
    </el-dialog>

    <!-- 执行预付 -->
    <el-dialog v-model="ppExecVisible" title="执行预付款（仅 ADMIN）" width="520px">
      <el-form label-width="110px">
        <el-form-item label="预付单号">
          <el-input :model-value="current && current.ppNo" readonly />
        </el-form-item>
        <el-form-item label="金额">
          <el-input :model-value="current && current.applyAmount" readonly />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="支付方式" required>
              <el-select v-model="ppExecForm.payMethod" style="width: 100%;">
                <el-option v-for="m in payMethods" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="银行账户" required>
              <el-select v-model="ppExecForm.bankAccountId" style="width: 100%;">
                <el-option v-for="b in banks" :key="b.id"
                  :label="`${b.accountName}（余额 ${b.balance}）`" :value="b.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="付款日期" required>
          <el-date-picker v-model="ppExecForm.payDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择日期" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ppExecVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitPrepayExecute">确认预付</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getStatementPageApi, createStatementApi, confirmStatementApi,
  getPaymentPageApi, getPaymentDetailApi, createPaymentApi, submitPaymentApi,
  cancelPaymentApi, schedulePaymentApi, waitFundsApi, executePaymentApi,
  getInvoiceCandidatesApi, getDeductCandidatesApi, getWriteoffPageApi,
  getPrepaymentPageApi, getPrepaymentDetailApi, createPrepaymentApi,
  submitPrepaymentApi, cancelPrepaymentApi, executePrepaymentApi,
  settlePrepaymentApi, getCleanupTodosApi
} from '@/api/fin/payment'
import { getBankAccountsApi } from '@/api/fin/bank-account'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { getPoCandidatesApi } from '@/api/proc/goods-receipt'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const roles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
// 申请/对账/冲抵 = ADMIN+PM；排期与执行 = ADMIN（Q5）
const canOperate = computed(() => isAdmin.value || roles.value.includes('ROLE_PM'))

const tab = ref('statement')
const loading = ref(false)
const submitting = ref(false)
const suppliers = ref([])
const banks = ref([])
const poCandidates = ref([])
const current = ref(null)
const payMethods = ['电汇', '支票', '商业汇票', '银行承兑汇票', '现金']
/** 本地日期（toISOString 为 UTC，跨日会慢一天） */
const todayLocal = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const STMT_TEXT = { ACCEPTED: '容差内', EXCEPTION: '差异冻结', CLOSED: '双签关闭' }
const STMT_TAG = { ACCEPTED: 'success', EXCEPTION: 'danger', CLOSED: 'info' }
const stmtText = s => STMT_TEXT[s] || s
const stmtTag = s => STMT_TAG[s] || 'info'
const REQ_TEXT = {
  DRAFT: '草稿', PENDING_APPROVE: '审批中', APPROVED: '已审批', REJECTED: '已驳回',
  SCHEDULED: '已排期', WAIT_FUNDS: '待付款', PAID: '已付款', CLOSED: '已关闭'
}
const REQ_TAG = {
  DRAFT: 'info', PENDING_APPROVE: 'warning', APPROVED: 'success', REJECTED: 'danger',
  SCHEDULED: 'primary', WAIT_FUNDS: 'warning', PAID: 'success', CLOSED: 'info'
}
const reqText = s => REQ_TEXT[s] || s
const reqTag = s => REQ_TAG[s] || 'info'

function onTab(name) {
  if (name === 'statement') loadStatements()
  if (name === 'request') loadRequests()
  if (name === 'execute') loadExec()
  if (name === 'writeoff') loadWriteoffs()
  if (name === 'prepayment') { loadPrepayments(); loadCleanup() }
}

// ---------------- Tab1 对账单 ----------------
const stmtRows = ref([])
const stmtTotal = ref(0)
const stmtFilters = reactive({ supplierId: '', status: '', current: 1, size: 10 })
const stmtVisible = ref(false)
const stmtForm = reactive({ supplierId: '', supplierName: '', stmtDate: '', stmtAmount: 0, remark: '' })

async function loadStatements() {
  loading.value = true
  try {
    const res = await getStatementPageApi({
      current: stmtFilters.current, size: stmtFilters.size,
      supplierId: stmtFilters.supplierId || undefined,
      status: stmtFilters.status || undefined
    })
    stmtRows.value = (res.data && res.data.records) || []
    stmtTotal.value = (res.data && res.data.total) || 0
  } finally { loading.value = false }
}

function openStatement() {
  stmtForm.supplierId = ''
  stmtForm.supplierName = ''
  stmtForm.stmtDate = todayLocal()
  stmtForm.stmtAmount = 0
  stmtForm.remark = ''
  stmtVisible.value = true
}

async function submitStatement() {
  if (!stmtForm.supplierId) { ElMessage.warning('请选择供应商'); return }
  if (!stmtForm.stmtDate) { ElMessage.warning('请选择对账日期'); return }
  submitting.value = true
  try {
    const res = await createStatementApi({ ...stmtForm })
    const s = res.data || {}
    ElMessage[s.status === 'EXCEPTION' ? 'error' : 'success'](
      s.status === 'EXCEPTION'
        ? `登记成功：差异 ${s.diffAmount}（差异率 ${s.diffRate}）已冻结该供应商付款，须双签解冻`
        : `登记成功：差异 ${s.diffAmount} 在容差内，未冻结`)
    stmtVisible.value = false
    await loadStatements()
  } finally { submitting.value = false }
}

async function doConfirmStatement(row) {
  try {
    await ElMessageBox.confirm(
      `对账单 ${row.stmtNo} 差异 ${row.diffAmount}（差异率 ${row.diffRate}），` +
      '确认后进入双签（采购员 → 财务），两签齐即解冻付款。是否发起？',
      '发起双签确认', { type: 'warning' })
  } catch (e) { return }
  try {
    await confirmStatementApi(row.id)
    ElMessage.success('已发起双签（采购员节点待签），冻结解除前付款申请仍会被阻断')
    await loadStatements()
  } catch (e) { /* 拦截器已提示 */ }
}

// ---------------- Tab2 付款申请 ----------------
const reqRows = ref([])
const reqTotal = ref(0)
const reqFilters = reactive({ supplierId: '', status: '', keyword: '', current: 1, size: 10 })
const reqVisible = ref(false)
const reqForm = reactive({ supplierId: '', supplierName: '', applyAmount: 0, invoiceIds: [], remark: '' })
const reqInvoices = ref([])
const reqSelectedUnpaid = computed(() =>
  reqInvoices.value.filter(i => reqForm.invoiceIds.includes(i.id))
    .reduce((s, i) => s + Number(i.unpaid || 0), 0).toFixed(2))

async function loadRequests() {
  loading.value = true
  try {
    const res = await getPaymentPageApi({
      current: reqFilters.current, size: reqFilters.size,
      status: reqFilters.status || undefined,
      keyword: reqFilters.keyword || undefined
    })
    reqRows.value = ((res.data && res.data.records) || []).map(r => ({ ...r, _detail: null }))
    reqTotal.value = (res.data && res.data.total) || 0
    reqRows.value.forEach(r => { if (r.status === 'PENDING_APPROVE') loadReqDetail(r) })
  } finally { loading.value = false }
}

async function loadReqDetail(row) {
  if (row._detail || row._loading) return
  row._loading = true
  try {
    const res = await getPaymentDetailApi(row.id)
    row._detail = res.data || {}
  } finally { row._loading = false }
}

function openCreateRequest() {
  reqForm.supplierId = ''
  reqForm.supplierName = ''
  reqForm.applyAmount = 0
  reqForm.invoiceIds = []
  reqForm.remark = ''
  reqInvoices.value = []
  reqVisible.value = true
}

async function onReqSupplierChange(sid) {
  reqForm.invoiceIds = []
  reqInvoices.value = []
  if (!sid) return
  const res = await getInvoiceCandidatesApi(sid)
  reqInvoices.value = res.data || []
}

async function submitCreateRequest() {
  if (!reqForm.supplierId) { ElMessage.warning('请选择供应商'); return }
  if (!reqForm.invoiceIds.length) { ElMessage.warning('请至少勾选一张发票'); return }
  submitting.value = true
  try {
    const supplier = suppliers.value.find(s => s.id === reqForm.supplierId)
    await createPaymentApi({
      supplierId: reqForm.supplierId,
      supplierName: supplier ? supplier.supplierName : '',
      applyAmount: reqForm.applyAmount,
      invoiceIds: reqForm.invoiceIds,
      remark: reqForm.remark || undefined
    })
    ElMessage.success('付款申请创建成功（草稿）')
    reqVisible.value = false
    await loadRequests()
  } finally { submitting.value = false }
}

async function doSubmit(row) {
  try {
    await submitPaymentApi(row.id)
    ElMessage.success('已提交分级审批（审批进度见展开区/审批待办）')
    await loadRequests()
  } catch (e) { /* 拦截器已提示 */ }
}

async function doResubmit(row) {
  try {
    await ElMessageBox.confirm(`申请 ${row.reqNo} 上次驳回：${row.rejectReason || '-'}。确认修改后重新提交？`,
      '改后重提', { type: 'warning' })
    await submitPaymentApi(row.id)
    ElMessage.success('已重新提交审批')
    await loadRequests()
  } catch (e) { /* 取消或拦截器提示 */ }
}

async function doCancel(row) {
  try {
    const { value } = await ElMessageBox.prompt('作废原因（必填）', '作废付款申请',
      { inputPlaceholder: '如：发票信息有误需重开' })
    if (!value || !value.trim()) { ElMessage.warning('作废原因必填'); return }
    await cancelPaymentApi(row.id, value.trim())
    ElMessage.success('已作废')
    await loadRequests()
  } catch (e) { /* 取消或拦截器提示 */ }
}

// ---------------- Tab3 排期与执行 ----------------
const execRows = ref([])
const execFilter = ref('SCHEDULED')
const schedVisible = ref(false)
const schedForm = reactive({ planDate: '' })
const execVisible = ref(false)
const execForm = reactive({
  payMethod: '电汇', bankAccountId: '', payDate: '', applyAmount: 0, deductIds: []
})
const deductList = ref([])
const execDeductSum = computed(() =>
  deductList.value.filter(d => execForm.deductIds.includes(d.id))
    .reduce((s, d) => s + Number(d.amount || 0), 0).toFixed(2))
const execActual = computed(() =>
  Math.max(0, Number(execForm.applyAmount || 0) - Number(execDeductSum.value)).toFixed(2))

async function loadExec() {
  loading.value = true
  try {
    const res = await getPaymentPageApi({ current: 1, size: 200 })
    let rows = (res.data && res.data.records) || []
    if (execFilter.value) rows = rows.filter(r => r.status === execFilter.value)
    execRows.value = rows.filter(r => ['APPROVED', 'SCHEDULED', 'WAIT_FUNDS', 'PAID'].includes(r.status))
  } finally { loading.value = false }
}

function openSchedule(row) {
  current.value = row
  schedForm.planDate = ''
  schedVisible.value = true
}

async function submitSchedule() {
  if (!schedForm.planDate) { ElMessage.warning('计划付款日必填'); return }
  submitting.value = true
  try {
    await schedulePaymentApi(current.value.id, schedForm.planDate)
    ElMessage.success('排期完成')
    schedVisible.value = false
    await loadExec()
  } finally { submitting.value = false }
}

async function doWaitFunds(row) {
  try {
    await waitFundsApi(row.id, row.status === 'SCHEDULED')
    ElMessage.success(row.status === 'SCHEDULED' ? '已标记待付款' : '已恢复排期')
    await loadExec()
  } catch (e) { /* 拦截器已提示 */ }
}

async function openExecute(row) {
  current.value = row
  execForm.payMethod = '电汇'
  execForm.bankAccountId = (banks.value[0] || {}).id || ''
  execForm.payDate = todayLocal()
  execForm.applyAmount = Math.max(0,
    Number(row.applyAmount || 0) - Number(row.executedAmount || 0))
  execForm.deductIds = []
  execVisible.value = true
  const [invRes, dedRes] = await Promise.all([
    getInvoiceCandidatesApi(row.supplierId).catch(() => ({ data: [] })),
    getDeductCandidatesApi(row.supplierId).catch(() => ({ data: [] }))
  ])
  deductList.value = dedRes.data || []
  void invRes
}

async function submitExecute() {
  if (!execForm.payMethod) { ElMessage.warning('请选择支付方式'); return }
  if (!execForm.bankAccountId) { ElMessage.warning('请选择银行账户'); return }
  if (!execForm.payDate) { ElMessage.warning('请选择付款日期'); return }
  if (!Number(execForm.applyAmount)) { ElMessage.warning('付款金额必须大于 0'); return }
  submitting.value = true
  try {
    const res = await executePaymentApi(current.value.id, {
      payMethod: execForm.payMethod,
      bankAccountId: execForm.bankAccountId,
      payDate: execForm.payDate,
      applyAmount: execForm.applyAmount,
      deductIds: execForm.deductIds
    })
    const d = res.data || {}
    ElMessage.success(`付款成功：${d.payNo} 实付 ${d.actualAmount}，核销 ${d.writeoffCount} 张发票，凭证 ${d.voucherNo}`)
    execVisible.value = false
    await loadExec()
    await loadBanks()
  } finally { submitting.value = false }
}

// ---------------- Tab4 核销记录 ----------------
const woRows = ref([])
const woTotal = ref(0)
const woFilters = reactive({ kind: 'PAYMENT', invoiceNo: '', current: 1, size: 10 })

async function loadWriteoffs() {
  loading.value = true
  try {
    const res = await getWriteoffPageApi({
      current: woFilters.current, size: woFilters.size,
      kind: woFilters.kind || undefined,
      invoiceNo: woFilters.invoiceNo || undefined
    })
    woRows.value = (res.data && res.data.records) || []
    woTotal.value = (res.data && res.data.total) || 0
  } finally { loading.value = false }
}

// ---------------- Tab5 预付款 ----------------
const ppRows = ref([])
const ppTotal = ref(0)
const ppFilters = reactive({ current: 1, size: 10 })
const cleanupTodos = ref([])
const settlePoNo = ref('')
const ppVisible = ref(false)
const ppForm = reactive({ poId: '', poNo: '', applyAmount: 0, remark: '' })
const ppExecVisible = ref(false)
const ppExecForm = reactive({ payMethod: '电汇', bankAccountId: '', payDate: '' })

async function loadPrepayments() {
  loading.value = true
  try {
    const res = await getPrepaymentPageApi({ current: ppFilters.current, size: ppFilters.size })
    ppRows.value = ((res.data && res.data.records) || []).map(r => ({ ...r, _detail: null }))
    ppTotal.value = (res.data && res.data.total) || 0
  } finally { loading.value = false }
}

async function loadCleanup() {
  try {
    const res = await getCleanupTodosApi()
    cleanupTodos.value = res.data || []
  } catch (e) { cleanupTodos.value = [] }
}

async function loadPpDetail(row) {
  if (row._detail || row._loading) return
  row._loading = true
  try {
    const res = await getPrepaymentDetailApi(row.id)
    row._detail = res.data || {}
  } finally { row._loading = false }
}

function openCreatePrepay() {
  ppForm.poId = ''
  ppForm.poNo = ''
  ppForm.applyAmount = 0
  ppForm.remark = ''
  ppVisible.value = true
}

function ppPoChange(poId) {
  const p = poCandidates.value.find(x => x.id === poId)
  ppForm.poNo = p ? p.poNo : ''
}

async function submitCreatePrepay() {
  if (!ppForm.poId) { ElMessage.warning('请选择 PO'); return }
  if (!Number(ppForm.applyAmount)) { ElMessage.warning('预付金额必须大于 0'); return }
  submitting.value = true
  try {
    await createPrepaymentApi({
      poId: ppForm.poId, applyAmount: ppForm.applyAmount, remark: ppForm.remark || undefined
    })
    ElMessage.success('预付款创建成功（草稿，双 L1 校验已通过）')
    ppVisible.value = false
    await loadPrepayments()
  } finally { submitting.value = false }
}

async function submitPrepay(row) {
  try {
    await submitPrepaymentApi(row.id)
    ElMessage.success('已提交分级审批')
    await loadPrepayments()
  } catch (e) { /* 拦截器已提示 */ }
}

async function cancelPrepay(row) {
  try {
    const { value } = await ElMessageBox.prompt('作废原因（必填）', '作废预付款',
      { inputPlaceholder: '如：合同条款变更不再预付' })
    if (!value || !value.trim()) { ElMessage.warning('作废原因必填'); return }
    await cancelPrepaymentApi(row.id, value.trim())
    ElMessage.success('已作废')
    await loadPrepayments()
  } catch (e) { /* 取消或拦截器提示 */ }
}

function openPrepayExecute(row) {
  current.value = row
  ppExecForm.payMethod = '电汇'
  ppExecForm.bankAccountId = (banks.value[0] || {}).id || ''
  ppExecForm.payDate = todayLocal()
  ppExecVisible.value = true
}

async function submitPrepayExecute() {
  if (!ppExecForm.bankAccountId) { ElMessage.warning('请选择银行账户'); return }
  if (!ppExecForm.payDate) { ElMessage.warning('请选择付款日期'); return }
  submitting.value = true
  try {
    const res = await executePrepaymentApi(current.value.id, { ...ppExecForm })
    const d = res.data || {}
    ElMessage.success(`预付成功：${d.payNo}，凭证 ${d.voucherNo}`)
    ppExecVisible.value = false
    await loadPrepayments()
    await loadBanks()
  } finally { submitting.value = false }
}

async function doSettle() {
  if (!settlePoNo.value) { ElMessage.warning('请输入 PO 号'); return }
  try {
    const res = await settlePrepaymentApi(settlePoNo.value)
    const n = (res.data && res.data.settled) || 0
    ElMessage[n > 0 ? 'success' : 'info'](n > 0 ? `冲抵 ${n} 笔完成` : '无可冲抵余额（幂等）')
    await loadPrepayments()
  } catch (e) { /* 拦截器已提示 */ }
}

async function settlePrepay(row) {
  try {
    const res = await settlePrepaymentApi(row.poNo)
    const n = (res.data && res.data.settled) || 0
    ElMessage[n > 0 ? 'success' : 'info'](n > 0 ? `冲抵 ${n} 笔完成` : '无可冲抵余额（幂等）')
    await loadPrepayments()
  } catch (e) { /* 拦截器已提示 */ }
}

// ---------------- 公共 ----------------
async function loadBanks() {
  const res = await getBankAccountsApi()
  banks.value = res.data || []
}

async function loadOptions() {
  const [{ data: sups }, { data: pos }] = await Promise.all([
    getSupplierPageApi({ current: 1, size: 200 }).catch(() => ({ data: [] })),
    getPoCandidatesApi().catch(() => ({ data: {} }))
  ])
  suppliers.value = sups.records || sups || []
  poCandidates.value = (pos && pos.records) || []
}

onMounted(async () => {
  await Promise.all([loadOptions(), loadBanks()])
  await loadStatements()
})
</script>

<style scoped>
.pay-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.sec { margin: 4px 0 8px; font-size: 13px; color: #303133; }
</style>
