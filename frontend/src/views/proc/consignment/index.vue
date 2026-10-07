<template>
  <div class="vmi-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="寄售采购 VMI（2.8.1）：物权属供应商，领用时点转移并生成应付暂估（FR-4.2-8-1~4）"
      description="协议维护/结算确认/处置确认限 ADMIN+PM，寄售领用过账限 ADMIN+WAREHOUSE；超最高水位收货阻断，采购员确认放行；账龄超 180 天未领用自动生成处置建议（BR-4.2-39）。" />

    <el-tabs v-model="tab">
      <!-- ============ Tab1 协议 ============ -->
      <el-tab-pane label="VMI 协议" name="agreement">
        <div class="toolbar">
          <el-select v-model="agFilters.supplierId" placeholder="供应商" clearable filterable
            style="width: 200px;" @change="loadAgreements">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
          <el-select v-model="agFilters.status" placeholder="状态" clearable style="width: 140px;"
            @change="loadAgreements">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="生效中" value="EFFECTIVE" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
          <el-button type="primary" v-if="canPm" @click="openAgDialog(null)">创建协议</el-button>
        </div>
        <el-table :data="agRows" size="small" border v-loading="loading"
          @expand-change="loadAgDetail">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div style="padding: 8px 16px;">
                <el-table :data="row._lines || []" size="mini" border>
                  <el-table-column prop="itemCode" label="物料" width="140" />
                  <el-table-column prop="itemName" label="名称" min-width="140" />
                  <el-table-column prop="unit" label="单位" width="60" />
                  <el-table-column prop="minQty" label="最低水位" width="90" />
                  <el-table-column prop="maxQty" label="最高水位" width="90" />
                  <el-table-column prop="unitPrice" label="协议单价" width="90" />
                  <el-table-column label="价格条款" width="200">
                    <template #default="{ row: l }">{{ l.priceStart }} ~ {{ l.priceEnd }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="agreementNo" label="协议编号" width="150" />
          <el-table-column prop="supplierName" label="供应商" min-width="150" />
          <el-table-column label="结算周期" width="80">
            <template #default="{ row }">{{ row.settleCycle === 'WEEK' ? '周结' : '月结' }}</template>
          </el-table-column>
          <el-table-column prop="effectiveDate" label="生效" width="100" />
          <el-table-column prop="expireDate" label="失效" width="100" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 'EFFECTIVE' ? 'success' : (row.status === 'DRAFT' ? 'info' : 'danger')" size="small">
                {{ agStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="lineCount" label="物料数" width="70" />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <template v-if="canPm">
                <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small"
                  @click="effectiveAg(row)">生效</el-button>
                <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small"
                  @click="openAgDialog(row)">编辑</el-button>
                <el-button v-if="row.status !== 'DISABLED'" link type="danger" size="small"
                  @click="disableAg(row)">停用</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="agTotal" :page-size="10"
          :current-page="agFilters.current" @current-change="p => { agFilters.current = p; loadAgreements() }" />
      </el-tab-pane>

      <!-- ============ Tab2 寄售库存 ============ -->
      <el-tab-pane label="寄售库存" name="stock">
        <div class="toolbar">
          <el-select v-model="stFilters.supplierId" placeholder="供应商" clearable filterable
            style="width: 200px;" @change="loadStocks">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
          <el-input v-model="stFilters.itemCode" placeholder="物料编码" clearable style="width: 160px;"
            @clear="loadStocks" @keyup.enter="loadStocks" />
          <el-button @click="loadStocks">查询</el-button>
          <span class="hint">加载即自动扫描补货建议与账龄处置（L4，design D4）</span>
        </div>
        <el-table :data="stRows" size="small" border v-loading="loading">
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="itemName" label="名称" min-width="140" />
          <el-table-column prop="batchNo" label="批次" width="130" />
          <el-table-column prop="supplierName" label="供应商" min-width="140" />
          <el-table-column prop="qty" label="现有量（物权=供应商）" width="130" />
          <el-table-column prop="issuedQty" label="累计领用" width="90" />
          <el-table-column prop="inboundDate" label="入库日期" width="100" />
          <el-table-column prop="ageDays" label="库龄(天)" width="85" />
          <el-table-column prop="agreeNo" label="关联协议" width="140" />
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="stTotal" :page-size="10"
          :current-page="stFilters.current" @current-change="p => { stFilters.current = p; loadStocks() }" />

        <el-divider content-position="left">账龄处置建议（BR-4.2-39，不阻断结算）</el-divider>
        <el-table :data="dpRows" size="small" border>
          <el-table-column prop="disposalNo" label="处置单号" width="150" />
          <el-table-column prop="itemCode" label="物料" width="130" />
          <el-table-column prop="batchNo" label="批次" width="120" />
          <el-table-column prop="supplierName" label="供应商" min-width="130" />
          <el-table-column prop="qty" label="数量" width="80" />
          <el-table-column prop="ageDays" label="库龄(天)" width="85" />
          <el-table-column label="建议" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.suggestType === 'CONVERT' ? 'warning' : 'info'">
                {{ row.suggestType === 'CONVERT' ? '转自有采购' : '退回供应商' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">{{ dpStatusText(row.status) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <template v-if="canPm && row.status === 'OPEN'">
                <el-button link type="primary" size="small" @click="openResolve(row)">处理</el-button>
                <el-button link type="danger" size="small" @click="cancelDisposal(row)">作废</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ Tab3 寄售领用 ============ -->
      <el-tab-pane label="寄售领用" name="issue">
        <div class="toolbar">
          <el-input v-model="isFilters.workOrderNo" placeholder="工单号" clearable style="width: 160px;"
            @clear="loadIssues" @keyup.enter="loadIssues" />
          <el-button @click="loadIssues">查询</el-button>
          <el-button type="primary" v-if="canWh" @click="openIssueDialog">发起寄售领用</el-button>
          <span class="hint">FIFO 配批 → 领用时点协议价 → 《寄售转自有凭证》+ 应付暂估（BR-4.2-37 / C-4.2-09）</span>
        </div>
        <el-table :data="isRows" size="small" border v-loading="loading" @expand-change="loadIssueDetail">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div style="padding: 8px 16px;">
                <el-table :data="row._lines || []" size="mini" border>
                  <el-table-column prop="itemCode" label="物料" width="140" />
                  <el-table-column prop="batchNo" label="批次" width="130" />
                  <el-table-column prop="qty" label="数量" width="80" />
                  <el-table-column prop="unitPrice" label="协议价" width="90" />
                  <el-table-column prop="amount" label="金额" width="100" />
                </el-table>
                <div v-if="row.transferDocNo" style="margin-top: 6px; font-size: 12px; color: #67C23A;">
                  《寄售转自有凭证》：{{ row.transferDocNo }}
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="issueNo" label="领料单号" width="150" />
          <el-table-column prop="workOrderNo" label="工单号" width="130" />
          <el-table-column prop="dept" label="部门" width="100" />
          <el-table-column prop="transferDocNo" label="转自有凭证" width="150" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'POSTED' ? 'success' : (row.status === 'DRAFT' ? 'warning' : 'info')">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <template v-if="canWh && row.status === 'DRAFT'">
                <el-button link type="primary" size="small" @click="postIssue(row)">过账</el-button>
                <el-button link type="danger" size="small" @click="cancelIssue(row)">作废</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="isTotal" :page-size="10"
          :current-page="isFilters.current" @current-change="p => { isFilters.current = p; loadIssues() }" />
      </el-tab-pane>

      <!-- ============ Tab4 VMI 结算 ============ -->
      <el-tab-pane label="VMI 结算" name="settlement">
        <div class="toolbar">
          <el-select v-model="seFilters.supplierId" placeholder="供应商" clearable filterable
            style="width: 200px;" @change="loadSettlements">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
          <el-select v-model="seFilters.status" placeholder="状态" clearable style="width: 140px;"
            @change="loadSettlements">
            <el-option label="待确认" value="DRAFT" />
            <el-option label="超容差挂起" value="ON_HOLD" />
            <el-option label="已确认" value="CONFIRMED" />
            <el-option label="已开票" value="INVOICED" />
          </el-select>
          <el-button type="primary" v-if="canPm" @click="openSettleDialog">生成结算单</el-button>
          <span class="hint">结算单 = Σ(领用量 × 领用时点协议价)；期末价差 &gt; 0.5% 挂起须采购员+供应商双确认（BR-4.2-38）</span>
        </div>
        <el-table :data="seRows" size="small" border v-loading="loading" @expand-change="loadSettleDetail">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div style="padding: 8px 16px;">
                <el-table :data="row._lines || []" size="mini" border>
                  <el-table-column prop="issueNo" label="领料单" width="150" />
                  <el-table-column prop="workOrderNo" label="工单号" width="130" />
                  <el-table-column prop="itemCode" label="物料" width="130" />
                  <el-table-column prop="batchNo" label="批次" width="120" />
                  <el-table-column prop="qty" label="数量" width="80" />
                  <el-table-column prop="unitPrice" label="领用时点协议价" width="120" />
                  <el-table-column prop="amount" label="金额" width="100" />
                </el-table>
                <div v-if="row.pmConfirmBy || row.supplierConfirmBy" style="margin-top: 6px; font-size: 12px; color: #909399;">
                  <span v-if="row.pmConfirmBy">采购员确认：{{ row.pmConfirmBy }} @ {{ row.pmConfirmAt }}</span>
                  <span v-if="row.supplierConfirmBy"> ｜ 供应商确认：{{ row.supplierConfirmBy }}（{{ row.supplierConfirmWay || '线下' }}）@ {{ row.supplierConfirmAt }}</span>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="settleNo" label="结算单号" width="150" />
          <el-table-column prop="supplierName" label="供应商" min-width="140" />
          <el-table-column label="结算期间" width="190">
            <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
          </el-table-column>
          <el-table-column prop="calcAmount" label="逐笔汇总" width="100" />
          <el-table-column prop="trialAmount" label="期末试算" width="100" />
          <el-table-column label="差率" width="90">
            <template #default="{ row }">{{ (parseFloat(row.diffRate) * 100).toFixed(2) }}%</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="seTag(row.status)">{{ seStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <template v-if="canPm && (row.status === 'DRAFT' || row.status === 'ON_HOLD')">
                <el-button v-if="!row.pmConfirmBy" link type="primary" size="small"
                  @click="signPm(row)">采购员确认</el-button>
                <el-button v-if="!row.supplierConfirmBy" link type="primary" size="small"
                  @click="openSupplierSign(row)">登记供应商确认</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="seTotal" :page-size="10"
          :current-page="seFilters.current" @current-change="p => { seFilters.current = p; loadSettlements() }" />
      </el-tab-pane>

      <!-- ============ Tab5 告警记录 ============ -->
      <el-tab-pane label="告警记录" name="alert">
        <div class="toolbar">
          <el-select v-model="alFilters.alertType" placeholder="类型" clearable style="width: 170px;"
            @change="loadAlerts">
            <el-option label="超最高水位" value="WATER_HIGH" />
            <el-option label="补货建议（低于最低水位）" value="REPLENISH" />
          </el-select>
          <el-select v-model="alFilters.status" placeholder="状态" clearable style="width: 130px;"
            @change="loadAlerts">
            <el-option label="待确认" value="OPEN" />
            <el-option label="已确认" value="CONFIRMED" />
            <el-option label="已处理" value="RESOLVED" />
          </el-select>
          <el-button @click="loadAlerts">刷新</el-button>
        </div>
        <el-table :data="alRows" size="small" border v-loading="loading">
          <el-table-column prop="alertNo" label="告警号" width="150" />
          <el-table-column label="类型" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="row.alertType === 'WATER_HIGH' ? 'danger' : 'warning'">
                {{ row.alertType === 'WATER_HIGH' ? '超最高水位' : '补货建议' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="itemCode" label="物料" width="130" />
          <el-table-column prop="supplierName" label="供应商" min-width="130" />
          <el-table-column prop="currentQty" label="当时库存" width="90" />
          <el-table-column prop="limitQty" label="水位阈值" width="90" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ alStatusText(row.status) }}</template>
          </el-table-column>
          <el-table-column label="确认留痕" min-width="200">
            <template #default="{ row }">
              <span v-if="row.confirmBy">{{ row.confirmBy }} @ {{ row.confirmAt }}<br v-if="row.confirmOpinion" />
                <span v-if="row.confirmOpinion" style="color:#909399;">{{ row.confirmOpinion }}</span></span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canPm && row.alertType === 'WATER_HIGH' && row.status === 'OPEN'"
                link type="primary" size="small" @click="openConfirmAlert(row)">确认放行</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="alTotal" :page-size="10"
          :current-page="alFilters.current" @current-change="p => { alFilters.current = p; loadAlerts() }" />
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 协议编辑对话框 ============ -->
    <el-dialog v-model="agDialog" :title="agForm.id ? '编辑协议' : '创建 VMI 协议'" width="880px"
      :close-on-click-modal="false">
      <el-form :model="agForm" label-width="90px" size="small">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="供应商" required>
              <el-select v-model="agForm.supplierId" filterable placeholder="选择供应商" style="width: 100%;">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="结算周期">
              <el-select v-model="agForm.settleCycle" style="width: 100%;">
                <el-option label="月结" value="MONTH" />
                <el-option label="周结" value="WEEK" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="物权转移">
              <el-input model-value="领用时转移" disabled />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="有效期" required>
              <el-date-picker v-model="agForm.dates" type="daterange" value-format="YYYY-MM-DD"
                start-placeholder="生效日" end-placeholder="失效日" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="备注">
              <el-input v-model="agForm.remark" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="物料清单" required>
          <el-table :data="agForm.lines" size="mini" border>
            <el-table-column label="物料编码" width="150">
              <template #default="{ row }"><el-input v-model="row.itemCode" size="small" /></template>
            </el-table-column>
            <el-table-column label="名称" width="140">
              <template #default="{ row }"><el-input v-model="row.itemName" size="small" /></template>
            </el-table-column>
            <el-table-column label="单位" width="70">
              <template #default="{ row }"><el-input v-model="row.unit" size="small" /></template>
            </el-table-column>
            <el-table-column label="最低水位" width="95">
              <template #default="{ row }"><el-input-number v-model="row.minQty" size="small" :min="0"
                :controls="false" style="width: 80px;" /></template>
            </el-table-column>
            <el-table-column label="最高水位" width="95">
              <template #default="{ row }"><el-input-number v-model="row.maxQty" size="small" :min="0"
                :controls="false" style="width: 80px;" /></template>
            </el-table-column>
            <el-table-column label="协议单价" width="100">
              <template #default="{ row }"><el-input-number v-model="row.unitPrice" size="small" :min="0.01"
                :precision="4" :controls="false" style="width: 88px;" /></template>
            </el-table-column>
            <el-table-column label="价格条款" width="230">
              <template #default="{ row }">
                <el-date-picker v-model="row.priceDates" type="daterange" value-format="YYYY-MM-DD"
                  start-placeholder="起" end-placeholder="止" size="small" style="width: 220px;" />
              </template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ $index }">
                <el-button link type="danger" size="small" @click="agForm.lines.splice($index, 1)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button size="small" style="margin-top: 6px;" @click="agForm.lines.push({ itemCode: '', itemName: '', unit: '', minQty: 0, maxQty: 0, unitPrice: 1, priceDates: [] })">+ 加物料</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="agDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="saveAgreement">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 领用对话框 ============ -->
    <el-dialog v-model="issueDialog" title="发起寄售领用（FIFO 配批）" width="760px"
      :close-on-click-modal="false">
      <el-form :model="issueForm" label-width="90px" size="small">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="供应商" required>
              <el-select v-model="issueForm.supplierId" filterable placeholder="寄售供应商" style="width: 100%;">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="工单号" required>
              <el-input v-model="issueForm.workOrderNo" placeholder="BR-4.2-37 逐笔记录" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="领料部门">
              <el-input v-model="issueForm.dept" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="用途">
              <el-input v-model="issueForm.purpose" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="14">
            <el-form-item label="物料编码" required>
              <el-input v-model="issueForm.itemCode" placeholder="如 RM0001000001" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="数量" required>
              <el-input-number v-model="issueForm.qty" :min="0.0001" :precision="3"
                :controls="false" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label-width="0">
              <el-button @click="doPreview" :disabled="!issueForm.supplierId || !issueForm.itemCode || !issueForm.qty">配批预检</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <el-table v-if="previewRows.length" :data="previewRows" size="mini" border
        style="margin-bottom: 8px;" max-height="220">
        <el-table-column prop="itemCode" label="物料" width="140" />
        <el-table-column prop="batchNo" label="FIFO 批次" width="150" />
        <el-table-column prop="qty" label="本批数量" width="100" />
        <el-table-column prop="stockType" label="来源" width="80" />
      </el-table>
      <el-alert v-if="previewErr" type="error" :closable="false" :title="previewErr" style="margin-bottom: 8px;" />
      <template #footer>
        <el-button @click="issueDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!previewRows.length"
          @click="createIssue">创建领料单</el-button>
      </template>
    </el-dialog>

    <!-- ============ 供应商确认登记对话框 ============ -->
    <el-dialog v-model="supSignDialog" title="登记供应商确认（线下确认，design D7）" width="460px">
      <el-form label-width="110px" size="small">
        <el-form-item label="结算单号">
          <el-input :model-value="supSignRow && supSignRow.settleNo" disabled />
        </el-form-item>
        <el-form-item label="供应商确认人" required>
          <el-input v-model="supSignForm.by" placeholder="供应商对账联系人" />
        </el-form-item>
        <el-form-item label="确认方式">
          <el-select v-model="supSignForm.way" style="width: 100%;">
            <el-option label="邮件回签" value="邮件回签" />
            <el-option label="对账单回签" value="对账单回签" />
            <el-option label="传真" value="传真" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="supSignDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="signSupplier">登记</el-button>
      </template>
    </el-dialog>

    <!-- ============ 处置处理对话框 ============ -->
    <el-dialog v-model="resolveDialog" title="处置建议处理（转自有 / 退回二选一）" width="460px">
      <el-form label-width="100px" size="small">
        <el-form-item label="处置方式" required>
          <el-radio-group v-model="resolveForm.via">
            <el-radio value="RETURN">退回供应商</el-radio>
            <el-radio value="CONVERT">转自有采购</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="resolveForm.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="resolveDisposal">处理完成</el-button>
      </template>
    </el-dialog>

    <!-- ============ 告警确认对话框 ============ -->
    <el-dialog v-model="alertDialog" title="超水位告警确认放行（BR-4.2-36）" width="460px">
      <el-form label-width="80px" size="small">
        <el-form-item label="告警号">
          <el-input :model-value="alertRow && alertRow.alertNo" disabled />
        </el-form-item>
        <el-form-item label="确认意见" required>
          <el-input v-model="alertOpinion" type="textarea" :rows="2" placeholder="如：供应商集中补货，同意本次超水位收货" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="alertDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmAlert">确认放行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAgreementPageApi, getAgreementDetailApi, createAgreementApi, updateAgreementApi,
  effectiveAgreementApi, disableAgreementApi, getAlertPageApi, confirmAlertApi,
  getVmiStockApi, getSettlementPageApi, getSettlementDetailApi, createSettlementApi,
  signSettlementApi, getDisposalPageApi, resolveDisposalApi, cancelDisposalApi
} from '@/api/proc/vmi'
import {
  getIssuePageApi, getIssueDetailApi, previewIssueApi, createIssueApi,
  postIssueApi, cancelIssueApi
} from '@/api/inv/issue'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const roles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
const canPm = computed(() => isAdmin.value || roles.value.includes('ROLE_PM'))
const canWh = computed(() => isAdmin.value || roles.value.includes('ROLE_WAREHOUSE'))

const tab = ref('agreement')
const loading = ref(false)
const submitting = ref(false)
const suppliers = ref([])

// ---------------- Tab1 协议 ----------------
const agRows = ref([])
const agTotal = ref(0)
const agFilters = reactive({ current: 1, supplierId: '', status: '' })
const agDialog = ref(false)
const agForm = reactive({ id: null, supplierId: '', settleCycle: 'MONTH', dates: [], remark: '', lines: [] })

async function loadAgreements() {
  loading.value = true
  try {
    const res = await getAgreementPageApi({
      current: agFilters.current, size: 10,
      supplierId: agFilters.supplierId || undefined, status: agFilters.status || undefined
    })
    agRows.value = res.data.records || []
    agTotal.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

async function loadAgDetail(row) {
  if (row._lines) return
  const res = await getAgreementDetailApi(row.id)
  row._lines = res.data.lines || []
}

function openAgDialog(row) {
  agForm.id = row ? row.id : null
  agForm.supplierId = row ? row.supplierId : ''
  agForm.settleCycle = row ? row.settleCycle : 'MONTH'
  agForm.dates = row ? [row.effectiveDate, row.expireDate] : []
  agForm.remark = row ? (row.remark || '') : ''
  agForm.lines = []
  agDialog.value = true
  if (row) {
    getAgreementDetailApi(row.id).then(res => {
      agForm.lines = (res.data.lines || []).map(l => ({
        itemCode: l.itemCode, itemName: l.itemName, unit: l.unit,
        minQty: Number(l.minQty || 0), maxQty: Number(l.maxQty || 0),
        unitPrice: Number(l.unitPrice || 1), priceDates: [l.priceStart, l.priceEnd]
      }))
    })
  } else {
    agForm.lines.push({ itemCode: '', itemName: '', unit: '', minQty: 0, maxQty: 0, unitPrice: 1, priceDates: [] })
  }
}

async function saveAgreement() {
  if (!agForm.supplierId) return ElMessage.warning('请选择供应商')
  if (!agForm.dates || agForm.dates.length !== 2) return ElMessage.warning('请选择协议有效期')
  const bad = agForm.lines.find(l => !l.itemCode || !l.priceDates || l.priceDates.length !== 2 || !(l.unitPrice > 0))
  if (bad) return ElMessage.warning('物料行须填编码、单价与价格条款')
  const payload = {
    supplierId: agForm.supplierId, settleCycle: agForm.settleCycle,
    effectiveDate: agForm.dates[0], expireDate: agForm.dates[1], remark: agForm.remark,
    lines: agForm.lines.map(l => ({
      itemCode: l.itemCode, itemName: l.itemName, unit: l.unit,
      minQty: l.minQty, maxQty: l.maxQty, unitPrice: l.unitPrice,
      priceStart: l.priceDates[0], priceEnd: l.priceDates[1]
    }))
  }
  submitting.value = true
  try {
    if (agForm.id) await updateAgreementApi(agForm.id, payload)
    else await createAgreementApi(payload)
    ElMessage.success('协议已保存')
    agDialog.value = false
    loadAgreements()
  } finally {
    submitting.value = false
  }
}

async function effectiveAg(row) {
  await effectiveAgreementApi(row.id)
  ElMessage.success('协议已生效')
  loadAgreements()
}

async function disableAg(row) {
  await ElMessageBox.confirm(`确认停用协议 ${row.agreementNo}？停用后不可再判定寄售 PO。`, '提示', { type: 'warning' })
  await disableAgreementApi(row.id)
  ElMessage.success('已停用')
  loadAgreements()
}

function agStatusText(s) {
  return { DRAFT: '草稿', EFFECTIVE: '生效中', EXPIRED: '过期', DISABLED: '停用' }[s] || s
}

// ---------------- Tab2 寄售库存 + 处置 ----------------
const stRows = ref([])
const stTotal = ref(0)
const stFilters = reactive({ current: 1, supplierId: '', itemCode: '' })
const dpRows = ref([])
const resolveDialog = ref(false)
const resolveRow = ref(null)
const resolveForm = reactive({ via: 'RETURN', note: '' })

async function loadStocks() {
  loading.value = true
  try {
    const res = await getVmiStockApi({
      current: stFilters.current, size: 10,
      supplierId: stFilters.supplierId || undefined, itemCode: stFilters.itemCode || undefined
    })
    stRows.value = res.data.records || []
    stTotal.value = Number(res.data.total || 0)
    const dp = await getDisposalPageApi({ current: 1, size: 50 })
    dpRows.value = dp.data.records || []
  } finally {
    loading.value = false
  }
}

function openResolve(row) {
  resolveRow.value = row
  resolveForm.via = row.suggestType
  resolveForm.note = ''
  resolveDialog.value = true
}

async function resolveDisposal() {
  submitting.value = true
  try {
    await resolveDisposalApi(resolveRow.value.id, { resolvedVia: resolveForm.via, note: resolveForm.note })
    ElMessage.success('处置已完成并留痕')
    resolveDialog.value = false
    loadStocks()
  } finally {
    submitting.value = false
  }
}

async function cancelDisposal(row) {
  const { value } = await ElMessageBox.prompt('作废原因（不少于 2 字）', '作废处置建议', { type: 'warning' })
  await cancelDisposalApi(row.id, value)
  ElMessage.success('已作废')
  loadStocks()
}

function dpStatusText(s) {
  return { OPEN: '待处理', DONE: '已完成', CANCELLED: '已作废' }[s] || s
}

// ---------------- Tab3 寄售领用 ----------------
const isRows = ref([])
const isTotal = ref(0)
const isFilters = reactive({ current: 1, workOrderNo: '' })
const issueDialog = ref(false)
const issueForm = reactive({ supplierId: '', workOrderNo: '', dept: '', purpose: '', itemCode: '', qty: 100 })
const previewRows = ref([])
const previewErr = ref('')

async function loadIssues() {
  loading.value = true
  try {
    const res = await getIssuePageApi({
      current: isFilters.current, size: 10, issueType: 'VMI',
      workOrderNo: isFilters.workOrderNo || undefined
    })
    isRows.value = res.data.records || []
    isTotal.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

async function loadIssueDetail(row) {
  if (row._lines) return
  const res = await getIssueDetailApi(row.id)
  row._lines = res.data.lines || []
}

function openIssueDialog() {
  previewRows.value = []
  previewErr.value = ''
  issueDialog.value = true
}

async function doPreview() {
  previewErr.value = ''
  previewRows.value = []
  try {
    const res = await previewIssueApi({
      issueType: 'VMI', supplierId: issueForm.supplierId, workOrderNo: issueForm.workOrderNo || 'PREVIEW',
      lines: [{ itemCode: issueForm.itemCode, qty: issueForm.qty }]
    })
    previewRows.value = res.data.lines || []
  } catch (e) {
    previewErr.value = (e && e.message) || '配批预检失败'
  }
}

async function createIssue() {
  submitting.value = true
  try {
    const res = await createIssueApi({
      issueType: 'VMI', supplierId: issueForm.supplierId, workOrderNo: issueForm.workOrderNo,
      dept: issueForm.dept, purpose: issueForm.purpose,
      lines: [{ itemCode: issueForm.itemCode, qty: issueForm.qty }]
    })
    ElMessage.success(`领料单 ${res.data.issue.issueNo} 已创建（DRAFT），请过账`)
    issueDialog.value = false
    loadIssues()
  } finally {
    submitting.value = false
  }
}

async function postIssue(row) {
  await ElMessageBox.confirm(`确认过账领料单 ${row.issueNo}？将执行物权转移并生成《寄售转自有凭证》与应付暂估。`,
    '过账确认', { type: 'warning' })
  const res = await postIssueApi(row.id)
  ElMessage.success(`过账成功，转自有凭证：${(res.data.issue && res.data.issue.transferDocNo) || ''}`)
  loadIssues()
}

async function cancelIssue(row) {
  const { value } = await ElMessageBox.prompt('作废原因（不少于 2 字）', '作废领料单', { type: 'warning' })
  await cancelIssueApi(row.id, value)
  ElMessage.success('已作废')
  loadIssues()
}

// ---------------- Tab4 结算 ----------------
const seRows = ref([])
const seTotal = ref(0)
const seFilters = reactive({ current: 1, supplierId: '', status: '' })
const supSignDialog = ref(false)
const supSignRow = ref(null)
const supSignForm = reactive({ by: '', way: '邮件回签' })

async function loadSettlements() {
  loading.value = true
  try {
    const res = await getSettlementPageApi({
      current: seFilters.current, size: 10,
      supplierId: seFilters.supplierId || undefined, status: seFilters.status || undefined
    })
    seRows.value = res.data.records || []
    seTotal.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

async function loadSettleDetail(row) {
  if (row._lines) return
  const res = await getSettlementDetailApi(row.id)
  row._lines = res.data.lines || []
}

async function openSettleDialog() {
  const { value } = await ElMessageBox.prompt(
    '输入供应商ID|开始日期|结束日期（如 sup-xxx|2026-09-01|2026-09-30），系统按期间聚合寄售领用生成结算单',
    '生成 VMI 结算单', { inputPattern: /^.+\\|.+\\|.+$/, inputErrorMessage: '格式：供应商ID|开始|结束' })
  const [supplierId, periodStart, periodEnd] = value.split('|').map(x => x.trim())
  const res = await createSettlementApi({ supplierId, periodStart, periodEnd })
  const s = res.data.settlement
  ElMessage.success(`结算单 ${s.settleNo} 已生成：${s.status === 'ON_HOLD' ? '超容差挂起，需双确认' : '容差内，待采购员确认'}`)
  loadSettlements()
}

async function signPm(row) {
  const res = await signSettlementApi(row.id, { side: 'pm' })
  ElMessage.success(res.data.confirmed ? '采购员已确认，结算单 CONFIRMED' : '采购员已确认（还需供应商确认）')
  loadSettlements()
}

function openSupplierSign(row) {
  supSignRow.value = row
  supSignForm.by = ''
  supSignForm.way = '邮件回签'
  supSignDialog.value = true
}

async function signSupplier() {
  if (!supSignForm.by) return ElMessage.warning('请填供应商确认人')
  submitting.value = true
  try {
    const res = await signSettlementApi(supSignRow.value.id,
      { side: 'supplier', supplierConfirmBy: supSignForm.by, way: supSignForm.way })
    ElMessage.success(res.data.confirmed ? '两签齐备，结算单 CONFIRMED' : '供应商确认已登记（还需采购员确认）')
    supSignDialog.value = false
    loadSettlements()
  } finally {
    submitting.value = false
  }
}

function seStatusText(s) {
  return { DRAFT: '待确认', ON_HOLD: '超容差挂起', CONFIRMED: '已确认', INVOICED: '已开票' }[s] || s
}

function seTag(s) {
  return { DRAFT: 'info', ON_HOLD: 'danger', CONFIRMED: 'success', INVOICED: 'warning' }[s] || 'info'
}

// ---------------- Tab5 告警 ----------------
const alRows = ref([])
const alTotal = ref(0)
const alFilters = reactive({ current: 1, alertType: '', status: '' })
const alertDialog = ref(false)
const alertRow = ref(null)
const alertOpinion = ref('')

async function loadAlerts() {
  loading.value = true
  try {
    const res = await getAlertPageApi({
      current: alFilters.current, size: 10,
      alertType: alFilters.alertType || undefined, status: alFilters.status || undefined
    })
    alRows.value = res.data.records || []
    alTotal.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

function openConfirmAlert(row) {
  alertRow.value = row
  alertOpinion.value = ''
  alertDialog.value = true
}

async function confirmAlert() {
  if (!alertOpinion.value || alertOpinion.value.trim().length < 2) {
    return ElMessage.warning('确认意见至少 2 字')
  }
  submitting.value = true
  try {
    await confirmAlertApi(alertRow.value.id, alertOpinion.value.trim())
    ElMessage.success('已确认放行：收货过账时携带 confirmWaterLevel=true 即可继续')
    alertDialog.value = false
    loadAlerts()
  } finally {
    submitting.value = false
  }
}

function alStatusText(s) {
  return { OPEN: '待确认', CONFIRMED: '已确认', RESOLVED: '已处理' }[s] || s
}

// ---------------- 初始化 ----------------
onMounted(async () => {
  const res = await getSupplierPageApi({ current: 1, size: 200 })
  suppliers.value = (res.data && res.data.records) || res.data || []
  await loadAgreements()
  await loadStocks()
  await loadIssues()
  await loadSettlements()
  await loadAlerts()
})
</script>

<style scoped>
.vmi-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
.hint { font-size: 12px; color: #909399; margin-left: auto; }
</style>
