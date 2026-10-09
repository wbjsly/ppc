<template>
  <div class="page">
    <el-tabs v-model="tab" @tab-change="onTabChange">
      <!-- ============ Tab1 PO 协同 ============ -->
      <el-tab-pane label="PO 协同" name="po">
        <div class="toolbar">
          <el-select v-model="poFilters.status" placeholder="确认状态" clearable style="width: 150px;" @change="loadPoCoops">
            <el-option label="已推送" value="PUSHED" />
            <el-option label="已确认" value="CONFIRMED" />
            <el-option label="变更待处理" value="CHANGE_PENDING" />
            <el-option label="已升级" value="ESCATED" />
          </el-select>
          <el-input v-model="poFilters.keyword" placeholder="PO 单号" clearable style="width: 180px;" @keyup.enter="loadPoCoops" />
          <el-button type="primary" @click="loadPoCoops">查询</el-button>
          <el-button @click="openLedger">推送台账</el-button>
          <el-button v-if="isAdmin" type="warning" plain @click="openUnlock">解锁供应商</el-button>
        </div>
        <el-table :data="poRows" size="small" border v-loading="loading" @expand-change="loadPoTimeline">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="timeline-box">
                <el-timeline v-if="timelineCache[row.id] && timelineCache[row.id].length">
                  <el-timeline-item v-for="t in timelineCache[row.id]" :key="t.id"
                    :timestamp="t.actionAt" :type="timelineType(t.actionType)">
                    【{{ actionLabel(t.actionType) }}】{{ t.source }}
                    <span v-if="t.operatorName"> · {{ t.operatorName }}</span>
                    <span v-if="t.promiseDate"> · 承诺 {{ t.promiseDate }}</span>
                    <div v-if="t.detail" class="tl-detail">{{ t.detail }}</div>
                  </el-timeline-item>
                </el-timeline>
                <el-empty v-else description="暂无协同动作" :image-size="40" />
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="poNo" label="PO 单号" width="150" />
          <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
          <el-table-column prop="poStatus" label="订单状态" width="90">
            <template #default="{ row }"><el-tag size="small">{{ row.poStatus }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="confirmStatus" label="交期确认" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="confirmTag(row.confirmStatus)">{{ confirmLabel(row.confirmStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="promiseDate" label="承诺交期" width="110" />
          <el-table-column label="超时(时)" width="86">
            <template #default="{ row }">
              <span :style="{ color: row.overdueHours > 0 ? '#F56C6C' : '' }">{{ row.overdueHours || 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column label="锁定" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.locked || row.coopLock === '1'" type="danger" size="small">LOCKED</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="pushedAt" label="推送时间" width="155" />
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canPm && row.confirmStatus !== 'CONFIRMED'" link type="primary"
                @click="openConfirm(row)">代录确认</el-button>
              <el-button v-if="canPm && row.confirmStatus === 'CHANGE_PENDING'" link type="warning"
                @click="openAccept(row)">接受改期</el-button>
              <el-button v-if="canPm && row.confirmStatus !== 'CONFIRMED'" link @click="doRemind(row)">催办</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next" :total="poTotal"
          :page-size="poFilters.size" v-model:current-page="poFilters.current" @current-change="loadPoCoops" />
      </el-tab-pane>

      <!-- ============ Tab2 ASN 与进度 ============ -->
      <el-tab-pane label="ASN 与进度" name="asn">
        <div class="toolbar">
          <el-select v-model="asnFilters.status" placeholder="状态" clearable style="width: 140px;" @change="loadAsns">
            <el-option label="待到货" value="CONFIRMED" />
            <el-option label="已核销" value="CLOSED" />
          </el-select>
          <el-input v-model="asnFilters.keyword" placeholder="ASN 单号" clearable style="width: 170px;" @keyup.enter="loadAsns" />
          <el-button type="primary" @click="loadAsns">查询</el-button>
          <el-button v-if="canPm" type="success" @click="openAsnCreate">代录创建 ASN</el-button>
          <span class="hint">到货登记支持「从 ASN 带出」（预填供应商/PO/物料/数量）</span>
        </div>
        <el-table :data="asnRows" size="small" border v-loading="loading">
          <el-table-column prop="asnNo" label="ASN 单号" width="160" />
          <el-table-column prop="poNo" label="PO" width="140" />
          <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
          <el-table-column prop="source" label="来源" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.source === 'REPLENISH' ? 'warning' : 'info'">{{ row.source }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'CLOSED' ? 'success' : 'primary'">
                {{ row.status === 'CLOSED' ? '已核销' : '待到货' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="超收" width="150">
            <template #default="{ row }">
              <template v-if="row.overStatus === 'PENDING'">
                <el-tag type="danger" size="small">待审批 {{ row.overToleranceQty }}</el-tag>
              </template>
              <template v-else-if="row.overStatus === 'RELEASED'">
                <el-tag type="success" size="small">已放行 {{ row.overToleranceQty }}</el-tag>
              </template>
              <template v-else-if="row.overStatus === 'REJECTED'">
                <el-tag type="info" size="small">已驳回</el-tag>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="expectArrival" label="预计到货" width="110" />
          <el-table-column label="到货偏差(h)" width="100">
            <template #default="{ row }">
              <span :style="{ color: Math.abs(row.arrivalDeviationHours || 0) > 24 ? '#E6A23C' : '' }">
                {{ row.arrivalDeviationHours == null ? '—' : row.arrivalDeviationHours }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openAsnDetail(row)">明细</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next" :total="asnTotal"
          :page-size="asnFilters.size" v-model:current-page="asnFilters.current" @current-change="loadAsns" />
      </el-tab-pane>

      <!-- ============ Tab3 VMI 协同 ============ -->
      <el-tab-pane label="VMI 协同" name="vmi">
        <div class="toolbar">
          <el-button v-if="canPm" type="primary" :loading="syncing" @click="doWaterSync">立即同步水位</el-button>
          <span v-if="waterResult" class="hint">
            同步时间 {{ waterResult.syncTime }} · {{ waterResult.written ? '已生成推送批次' : '无变化（幂等跳过）' }}
          </span>
          <el-button @click="loadAlerts">刷新补货建议</el-button>
        </div>
        <template v-if="waterItems.length">
          <div class="sub-title">水位快照（批次台账见「推送台账」→ VMI.WATER_SYNCED）</div>
          <el-table :data="waterItems" size="small" border>
            <el-table-column prop="itemCode" label="物料" width="160" />
            <el-table-column prop="itemName" label="名称" min-width="140" show-overflow-tooltip />
            <el-table-column prop="qty" label="寄售库存" width="100" />
            <el-table-column prop="min" label="最低水位" width="100" />
            <el-table-column prop="max" label="最高水位" width="100" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'LOW' ? 'danger' : (row.status === 'HIGH' ? 'warning' : 'success')">
                  {{ row.needReplenish ? 'LOW·补货' : row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <div class="sub-title" style="margin-top: 14px;">补货建议（供应商确认 / 代录）</div>
        <el-table :data="alertRows" size="small" border v-loading="loading">
          <el-table-column prop="alertNo" label="告警号" width="150" />
          <el-table-column prop="itemName" label="物料" min-width="140" show-overflow-tooltip />
          <el-table-column prop="currentQty" label="当前库存" width="100" />
          <el-table-column prop="limitQty" label="最低水位" width="100" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : (row.status === 'CONFIRMED' ? 'warning' : 'success')">
                {{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canPm && row.status === 'OPEN'" link type="primary"
                @click="openReplenishConfirm(row)">代录确认</el-button>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="sub-title" style="margin-top: 14px;">结算单（推送与代录确认）</div>
        <el-table :data="settleRows" size="small" border>
          <el-table-column prop="settleNo" label="结算单号" width="150" />
          <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
          <el-table-column prop="calcAmount" label="结算金额" width="110" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : (row.status === 'ON_HOLD' ? 'warning' : 'info')">
                {{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canPm && row.status !== 'CONFIRMED' && row.status !== 'INVOICED'" link
                type="primary" @click="openSettleSign(row)">代录供应商确认</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ Tab4 对账协同 ============ -->
      <el-tab-pane label="对账协同" name="recon">
        <div class="toolbar">
          <el-select v-model="reconFilters.status" placeholder="状态" clearable style="width: 150px;" @change="loadStatements">
            <el-option label="容差内" value="ACCEPTED" />
            <el-option label="差异冻结" value="EXCEPTION" />
            <el-option label="双签关闭" value="CLOSED" />
          </el-select>
          <el-button type="primary" @click="loadStatements">查询</el-button>
          <span class="hint">系统匹配（S-4.9-06）：ERP 入库/领用明细 vs 供应商对账单，差异标红；冻结与双签归 2.7.3</span>
        </div>
        <el-alert v-if="reconDenied" type="info" :closable="false" show-icon
          title="对账协同查询仅开放 ADMIN/PM（2.7.3 口径），当前角色不可见" />
        <el-table :data="reconRows" size="small" border v-loading="loading">
          <el-table-column prop="stmtNo" label="对账单号" width="150" />
          <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
          <el-table-column prop="stmtDate" label="对账日期" width="110" />
          <el-table-column prop="stmtAmount" label="对账金额" width="110" />
          <el-table-column prop="baseAmount" label="暂估基准" width="110" />
          <el-table-column label="差异率" width="90">
            <template #default="{ row }">{{ pct(row.diffRate) }}</template>
          </el-table-column>
          <el-table-column label="冻结" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'EXCEPTION'" type="danger" size="small">冻结中</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="匹配" width="140">
            <template #default="{ row }">
              <span v-if="row.matchAt">{{ row.matchBy }} @ {{ row.matchAt }}</span>
              <el-tag v-else size="small" type="info">未匹配</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canPm" link type="primary" @click="doMatch(row)">系统匹配</el-button>
              <el-button link @click="openReconDetail(row)">差异清单</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next" :total="reconTotal"
          :page-size="reconFilters.size" v-model:current-page="reconFilters.current" @current-change="loadStatements" />
      </el-tab-pane>
    </el-tabs>

    <!-- 代录确认对话框 -->
    <el-dialog v-model="confirmDlg" title="代录交期确认（来源 OFFLINE）" width="440px">
      <el-form label-width="96px">
        <el-form-item label="PO"><span>{{ currentPo.poNo }}</span></el-form-item>
        <el-form-item label="承诺交期">
          <el-date-picker v-model="confirmForm.promiseDate" type="date" value-format="YYYY-MM-DD"
            placeholder="原交期可留空默认" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="confirmForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitConfirm">确认</el-button>
      </template>
    </el-dialog>

    <!-- 接受改期对话框 -->
    <el-dialog v-model="acceptDlg" title="接受供应商改期申请" width="440px">
      <el-form label-width="96px">
        <el-form-item label="PO"><span>{{ currentPo.poNo }}</span></el-form-item>
        <el-form-item label="最终承诺交期" required>
          <el-date-picker v-model="acceptForm.promiseDate" type="date" value-format="YYYY-MM-DD"
            style="width: 100%;" />
        </el-form-item>
        <el-form-item label="意见"><el-input v-model="acceptForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAccept">接受并确认</el-button>
      </template>
    </el-dialog>

    <!-- 解锁对话框 -->
    <el-dialog v-model="unlockDlg" title="解锁供应商确认入口（ADMIN）" width="440px">
      <el-form label-width="96px">
        <el-form-item label="供应商 ID" required><el-input v-model="unlockForm.supplierId" /></el-form-item>
        <el-form-item label="解锁原因"><el-input v-model="unlockForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="unlockDlg = false">取消</el-button>
        <el-button type="warning" :loading="submitting" @click="submitUnlock">确认解锁</el-button>
      </template>
    </el-dialog>

    <!-- 推送台账对话框 -->
    <el-dialog v-model="ledgerDlg" title="推送台账（outbox 事件）" width="860px">
      <div class="toolbar">
        <el-select v-model="ledgerFilter" placeholder="事件类型" clearable style="width: 240px;" @change="loadLedger">
          <el-option label="PO 下达推送" value="PROC.PO_PUSHED" />
          <el-option label="交期确认" value="PROC.PO_CONFIRMED" />
          <el-option label="ASN 创建" value="PROC.ASN_CREATED" />
          <el-option label="水位同步" value="VMI.WATER_SYNCED" />
          <el-option label="补货推送/确认" value="VMI.REPLENISH_PUSHED" />
          <el-option label="结算推送" value="VMI.SETTLE_PUSHED" />
        </el-select>
        <el-input v-model="ledgerKeyword" placeholder="业务单号/供应商" clearable style="width: 200px;" @keyup.enter="loadLedger" />
        <el-button type="primary" @click="loadLedger">查询</el-button>
      </div>
      <el-table :data="ledgerRows" size="small" border max-height="420">
        <el-table-column prop="eventType" label="事件类型" width="180" />
        <el-table-column prop="bizCode" label="业务键" min-width="200" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column prop="occurredAt" label="发生时间" width="160" />
        <el-table-column prop="summary" label="摘要" min-width="220" show-overflow-tooltip />
      </el-table>
      <el-pagination style="margin-top: 8px;" layout="prev, pager, next" :total="ledgerTotal"
        :page-size="20" v-model:current-page="ledgerCurrent" @current-change="loadLedger" />
    </el-dialog>

    <!-- ASN 明细对话框 -->
    <el-dialog v-model="asnDlg" :title="`ASN 明细 - ${currentAsn.asnNo || ''}`" width="760px">
      <el-descriptions :column="3" border size="small" style="margin-bottom: 10px;">
        <el-descriptions-item label="PO">{{ currentAsn.poNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="供应商">{{ currentAsn.supplierName }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ currentAsn.source }}</el-descriptions-item>
        <el-descriptions-item label="预计到货">{{ currentAsn.expectArrival || '—' }}</el-descriptions-item>
        <el-descriptions-item label="物流单号">{{ currentAsn.logisticsNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="超收状态">{{ currentAsn.overStatus }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="currentAsn.lines || []" size="small" border>
        <el-table-column prop="lineNo" label="#" width="50" />
        <el-table-column prop="itemCode" label="物料" width="150" />
        <el-table-column prop="itemName" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column prop="qty" label="发货量" width="90" />
        <el-table-column prop="overQty" label="超收量" width="90" />
        <el-table-column prop="receivedQty" label="已核销" width="90" />
        <el-table-column prop="lineStatus" label="行状态" width="90" />
      </el-table>
      <div v-if="currentAsn.overStatus === 'PENDING'" class="hint" style="margin-top: 8px; color: #E6A23C;">
        超收 {{ currentAsn.overToleranceQty }} 待 PM+仓库双签放行（未放行部分不可收货）
      </div>
      <template #footer>
        <el-button v-if="currentAsn.overStatus === 'PENDING' && (canPm || canWh)" type="primary"
          :loading="submitting" @click="signOverApproval">签署超收审批</el-button>
        <el-button @click="asnDlg = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 代录创建 ASN -->
    <el-dialog v-model="asnCreateDlg" title="代录创建 ASN（按已确认交期的 PO）" width="720px">
      <el-form label-width="90px">
        <el-form-item label="PO" required>
          <el-select v-model="asnForm.poId" filterable placeholder="选择交期已确认的 PO" style="width: 100%;"
            @change="onAsnPoChange">
            <el-option v-for="p in confirmedPos" :key="p.id" :label="`${p.poNo}（${p.supplierName}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="预计到货">
          <el-date-picker v-model="asnForm.expectArrival" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="物流单号"><el-input v-model="asnForm.logisticsNo" /></el-form-item>
      </el-form>
      <el-table :data="asnForm.lines" size="small" border>
        <el-table-column label="PO 行" width="220">
          <template #default="{ row }">
            <el-select v-model="row.poLineId" filterable style="width: 100%;" placeholder="选择 PO 行">
              <el-option v-for="l in asnPoLines" :key="l.id"
                :label="`${l.itemCode}（未清 ${(l.qty || 0) - (l.receivedQty || 0)}）`" :value="l.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="发货数量" width="160">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="0.001" :precision="3" controls-position="right" style="width: 100%;" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ $index }">
            <el-button link type="danger" @click="asnForm.lines.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button link type="primary" style="margin-top: 6px;" @click="asnForm.lines.push({ poLineId: null, qty: 1 })">+ 加一行</el-button>
      <template #footer>
        <el-button @click="asnCreateDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAsnCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 补货代录确认 -->
    <el-dialog v-model="replenishDlg" title="补货建议代录确认（联动创建 ASN）" width="440px">
      <el-form label-width="96px">
        <el-form-item label="物料"><span>{{ currentAlert.itemName || currentAlert.itemCode }}</span></el-form-item>
        <el-form-item label="当前/水位">{{ currentAlert.currentQty }} / {{ currentAlert.limitQty }}</el-form-item>
        <el-form-item label="确认量">
          <el-input-number v-model="replenishQty" :min="0.001" :precision="3" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="replenishDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReplenish">确认并生成 ASN</el-button>
      </template>
    </el-dialog>

    <!-- 结算代录确认 -->
    <el-dialog v-model="settleSignDlg" title="代录供应商结算确认（来源 OFFLINE）" width="440px">
      <el-form label-width="110px">
        <el-form-item label="结算单"><span>{{ currentSettle.settleNo }}</span></el-form-item>
        <el-form-item label="供应商确认人" required><el-input v-model="settleSignBy" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="settleSignDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitSettleSign">确认</el-button>
      </template>
    </el-dialog>

    <!-- 对账差异清单 -->
    <el-dialog v-model="reconDlg" :title="`匹配结果 - ${currentRecon.stmtNo || ''}`" width="820px">
      <template v-if="matchView">
        <el-descriptions :column="4" border size="small" style="margin-bottom: 10px;">
          <el-descriptions-item label="ERP 侧合计">{{ matchView.erpTotal }}</el-descriptions-item>
          <el-descriptions-item label="供应商侧合计">{{ matchView.supplierTotal }}</el-descriptions-item>
          <el-descriptions-item label="差异">{{ matchView.diffAmount }}</el-descriptions-item>
          <el-descriptions-item label="差异率">{{ pct(matchView.diffRate) }}</el-descriptions-item>
        </el-descriptions>
        <div class="sub-title">逐笔匹配（{{ (matchView.matched || []).length }} 笔一致）</div>
        <el-table :data="matchView.matched || []" size="small" border max-height="180">
          <el-table-column prop="supplierDesc" label="供应商侧" min-width="140" />
          <el-table-column prop="supplierAmount" label="金额" width="100" />
          <el-table-column prop="accrualNo" label="ERP 暂估单" width="160" />
          <el-table-column prop="postingDocNo" label="凭证号" width="140" />
        </el-table>
        <div class="sub-title" style="margin-top: 10px; color: #F56C6C;">
          差异清单（标红 {{ matchView.redCount || 0 }} 笔）
        </div>
        <el-table :data="redRows" size="small" border max-height="200">
          <el-table-column prop="flag" label="类型" width="140">
            <template #default="{ row }">
              <el-tag type="danger" size="small">{{ row.flag }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="desc" label="说明" min-width="160">
            <template #default="{ row }">{{ row.desc || row.accrualNo }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="110">
            <template #default="{ row }">{{ row.amount != null ? row.amount : row.openAmount }}</template>
          </el-table-column>
          <el-table-column prop="postingDocNo" label="凭证号" width="150" />
        </el-table>
        <div class="hint" style="margin-top: 8px;">匹配人 {{ matchView.matchBy }} @ {{ matchView.matchAt }}</div>
      </template>
      <el-empty v-else description="尚未执行系统匹配" />
      <template #footer>
        <el-button v-if="canPm && currentRecon.id" type="primary" :loading="submitting"
          @click="doMatch(currentRecon)">执行系统匹配</el-button>
        <el-button @click="reconDlg = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import request from '@/utils/request'
import {
  getPoCoopPageApi, getPoCoopTimelineApi, remindPoApi, offlineConfirmPoApi,
  acceptPoChangeApi, unlockPoSupplierApi, getAsnPageApi, getAsnDetailApi,
  createAsnApi, triggerWaterSyncApi, getPortalEventLedgerApi, matchStatementApi
} from '@/api/proc/portal-collaboration'
import { getAlertPageApi, getSettlementPageApi, signSettlementApi } from '@/api/proc/vmi'
import { getStatementPageApi, getStatementDetailApi } from '@/api/fin/payment'
import { getQmsApprovalTodoApi, passQmsApprovalApi } from '@/api/qms/approval'
import { getPoDetailApi } from '@/api/proc/purchase-order'

const userStore = useUserStore()
const roles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
const canPm = computed(() => isAdmin.value || roles.value.includes('ROLE_PM'))
const canWh = computed(() => isAdmin.value || roles.value.includes('ROLE_WAREHOUSE'))

const tab = ref('po')
const loading = ref(false)
const submitting = ref(false)
// 对账单查询规则为 ADMIN/PM（2.7.3 既有口径）：WAREHOUSE 触发会 403，
// 而全局拦截器将 403 视同 401 清 token → 仅授权角色加载（design D13 对账 Tab 收敛）
const canViewRecon = computed(() => isAdmin.value || canPm.value)
const reconLoaded = ref(false)
const reconDenied = ref(false)

// ---------------- Tab1 PO 协同 ----------------
const poRows = ref([])
const poTotal = ref(0)
const poFilters = reactive({ current: 1, size: 20, status: '', keyword: '' })
const timelineCache = ref({})
const confirmDlg = ref(false)
const acceptDlg = ref(false)
const unlockDlg = ref(false)
const ledgerDlg = ref(false)
const currentPo = ref({})
const confirmForm = reactive({ promiseDate: '', remark: '' })
const acceptForm = reactive({ promiseDate: '', remark: '' })
const unlockForm = reactive({ supplierId: '', remark: '' })
const ledgerRows = ref([])
const ledgerTotal = ref(0)
const ledgerCurrent = ref(1)
const ledgerFilter = ref('')
const ledgerKeyword = ref('')

async function loadPoCoops() {
  loading.value = true
  try {
    const { data } = await getPoCoopPageApi({
      current: poFilters.current, size: poFilters.size,
      status: poFilters.status || undefined, keyword: poFilters.keyword || undefined
    })
    poRows.value = data.records || []
    poTotal.value = Number(data.total) || 0
  } finally {
    loading.value = false
  }
}

async function loadPoTimeline(row) {
  if (timelineCache.value[row.id]) return
  const { data } = await getPoCoopTimelineApi(row.id)
  timelineCache.value[row.id] = data || []
}

function openConfirm(row) {
  currentPo.value = row
  confirmForm.promiseDate = row.promiseDate || ''
  confirmForm.remark = ''
  confirmDlg.value = true
}

async function submitConfirm() {
  submitting.value = true
  try {
    await offlineConfirmPoApi(currentPo.value.id, { ...confirmForm })
    ElMessage.success('交期确认已登记（OFFLINE）')
    confirmDlg.value = false
    delete timelineCache.value[currentPo.value.id]
    await loadPoCoops()
  } finally {
    submitting.value = false
  }
}

function openAccept(row) {
  currentPo.value = row
  acceptForm.promiseDate = row.promiseDate || ''
  acceptForm.remark = ''
  acceptDlg.value = true
}

async function submitAccept() {
  if (!acceptForm.promiseDate) {
    ElMessage.warning('请选择最终承诺交期')
    return
  }
  submitting.value = true
  try {
    await acceptPoChangeApi(currentPo.value.id, { ...acceptForm })
    ElMessage.success('已接受改期并确认交期')
    acceptDlg.value = false
    await loadPoCoops()
  } finally {
    submitting.value = false
  }
}

async function doRemind(row) {
  await remindPoApi(row.id)
  ElMessage.success('催办已补发')
  delete timelineCache.value[row.id]
  await loadPoCoops()
}

function openUnlock() {
  unlockForm.supplierId = ''
  unlockForm.remark = ''
  unlockDlg.value = true
}

async function submitUnlock() {
  if (!unlockForm.supplierId) {
    ElMessage.warning('请填写供应商 ID')
    return
  }
  submitting.value = true
  try {
    await unlockPoSupplierApi({ ...unlockForm })
    ElMessage.success('已解锁')
    unlockDlg.value = false
    await loadPoCoops()
  } finally {
    submitting.value = false
  }
}

async function openLedger() {
  ledgerDlg.value = true
  ledgerCurrent.value = 1
  await loadLedger()
}

async function loadLedger() {
  const { data } = await getPortalEventLedgerApi({
    current: ledgerCurrent.value, size: 20,
    eventType: ledgerFilter.value || undefined,
    keyword: ledgerKeyword.value || undefined
  })
  ledgerRows.value = data.records || []
  ledgerTotal.value = Number(data.total) || 0
}

// ---------------- Tab2 ASN ----------------
const asnRows = ref([])
const asnTotal = ref(0)
const asnFilters = reactive({ current: 1, size: 20, status: '', keyword: '' })
const asnDlg = ref(false)
const asnCreateDlg = ref(false)
const currentAsn = ref({})
const confirmedPos = ref([])
const asnPoLines = ref([])
const asnForm = reactive({ poId: '', expectArrival: '', logisticsNo: '', lines: [{ poLineId: null, qty: 1 }] })

async function loadAsns() {
  loading.value = true
  try {
    const { data } = await getAsnPageApi({
      current: asnFilters.current, size: asnFilters.size,
      status: asnFilters.status || undefined, keyword: asnFilters.keyword || undefined
    })
    asnRows.value = data.records || []
    asnTotal.value = Number(data.total) || 0
  } finally {
    loading.value = false
  }
}

async function openAsnDetail(row) {
  const { data } = await getAsnDetailApi(row.id)
  currentAsn.value = data
  asnDlg.value = true
}

async function openAsnCreate() {
  // 交期已确认的 PO 作为来源
  const { data } = await getPoCoopPageApi({ current: 1, size: 100, status: 'CONFIRMED' })
  confirmedPos.value = data.records || []
  asnForm.poId = ''
  asnForm.expectArrival = ''
  asnForm.logisticsNo = ''
  asnForm.lines = [{ poLineId: null, qty: 1 }]
  asnPoLines.value = []
  asnCreateDlg.value = true
}

async function onAsnPoChange(poId) {
  const { data } = await getPoDetailApi(poId)
  asnPoLines.value = (data && data.lines) || []
  asnForm.lines = [{ poLineId: null, qty: 1 }]
}

async function submitAsnCreate() {
  if (!asnForm.poId) {
    ElMessage.warning('请选择 PO')
    return
  }
  const lines = asnForm.lines.filter(l => l.poLineId && l.qty > 0)
  if (!lines.length) {
    ElMessage.warning('至少一行有效明细')
    return
  }
  submitting.value = true
  try {
    await createAsnApi({
      poId: asnForm.poId, lines,
      expectArrival: asnForm.expectArrival || undefined,
      logisticsNo: asnForm.logisticsNo || undefined
    })
    ElMessage.success('ASN 创建成功')
    asnCreateDlg.value = false
    await loadAsns()
  } finally {
    submitting.value = false
  }
}

async function signOverApproval() {
  try {
    await ElMessageBox.confirm('确认放行该 ASN 的超收部分？（PM+仓库双签之一）', '超收审批', { type: 'warning' })
  } catch { return }
  submitting.value = true
  try {
    const { data } = await getQmsApprovalTodoApi()
    const task = (data || []).find(t => t.bizType === 'AsnOverTolerance' && t.bizId === currentAsn.value.id)
    if (!task) {
      ElMessage.warning('未找到可签的超收审批节点（需 PM 与仓库分别签署）')
      return
    }
    await passQmsApprovalApi(task.taskId, '同意超收放行')
    ElMessage.success('已签署')
    const d = await getAsnDetailApi(currentAsn.value.id)
    currentAsn.value = d
    await loadAsns()
  } finally {
    submitting.value = false
  }
}

// ---------------- Tab3 VMI ----------------
const syncing = ref(false)
const waterResult = ref(null)
const waterItems = ref([])
const alertRows = ref([])
const settleRows = ref([])
const replenishDlg = ref(false)
const currentAlert = ref({})
const replenishQty = ref(1)
const settleSignDlg = ref(false)
const currentSettle = ref({})
const settleSignBy = ref('')

async function doWaterSync() {
  syncing.value = true
  try {
    const { data } = await triggerWaterSyncApi({})
    waterResult.value = data
    const sups = data.suppliers || []
    waterItems.value = sups.length ? sups[0].items || [] : []
    ElMessage.success(data.written ? '水位同步完成（已生成推送批次）' : '水位无变化（幂等跳过）')
  } finally {
    syncing.value = false
  }
}

async function loadAlerts() {
  const { data } = await getAlertPageApi({ current: 1, size: 50, alertType: 'REPLENISH' })
  alertRows.value = data.records || []
}

async function loadSettlements() {
  const { data } = await getSettlementPageApi({ current: 1, size: 50 })
  settleRows.value = data.records || []
}

function openReplenishConfirm(row) {
  currentAlert.value = row
  replenishQty.value = Math.max(0.001, Number(row.limitQty || 1) - Number(row.currentQty || 0))
  replenishDlg.value = true
}

async function submitReplenish() {
  submitting.value = true
  try {
    const r = await request.post(`/proc/vmi/replenish-confirms/${currentAlert.value.id}/confirm`,
      { confirmQty: replenishQty.value })
    ElMessage.success(`已确认，生成 ASN ${r.data.asnNo || ''}`)
    replenishDlg.value = false
    await loadAlerts()
    await loadAsns()
  } finally {
    submitting.value = false
  }
}

function openSettleSign(row) {
  currentSettle.value = row
  settleSignBy.value = ''
  settleSignDlg.value = true
}

async function submitSettleSign() {
  if (!settleSignBy.value) {
    ElMessage.warning('请填写线下供应商确认人')
    return
  }
  submitting.value = true
  try {
    await signSettlementApi(currentSettle.value.id, {
      side: 'supplier', supplierConfirmBy: settleSignBy.value, source: 'OFFLINE'
    })
    ElMessage.success('已登记供应商确认（OFFLINE）')
    settleSignDlg.value = false
    await loadSettlements()
  } finally {
    submitting.value = false
  }
}

// ---------------- Tab4 对账协同 ----------------
const reconRows = ref([])
const reconTotal = ref(0)
const reconFilters = reactive({ current: 1, size: 20, status: '' })
const reconDlg = ref(false)
const currentRecon = ref({})
const matchView = ref(null)
const redRows = computed(() => [
  ...(matchView.value?.supplierOnly || []),
  ...(matchView.value?.erpOnly || [])
])

async function loadStatements() {
  loading.value = true
  try {
    const { data } = await getStatementPageApi({
      current: reconFilters.current, size: reconFilters.size,
      status: reconFilters.status || undefined
    })
    reconRows.value = data.records || []
    reconTotal.value = Number(data.total) || 0
  } finally {
    loading.value = false
  }
}

async function doMatch(row) {
  submitting.value = true
  try {
    const { data } = await matchStatementApi(row.id, {})
    matchView.value = data
    ElMessage.success(`匹配完成：差异 ${data.diffAmount}，标红 ${data.redCount} 笔`)
    await loadStatements()
    if (reconDlg.value) {
      currentRecon.value = row
    }
  } finally {
    submitting.value = false
  }
}

async function openReconDetail(row) {
  currentRecon.value = row
  const { data } = await getStatementDetailApi(row.id)
  matchView.value = null
  if (data && data.matchResult) {
    try {
      matchView.value = JSON.parse(data.matchResult)
    } catch { matchView.value = null }
  }
  reconDlg.value = true
}

function onTabChange(name) {
  if (name === 'recon') ensureRecon()
}

// ---------------- 工具 ----------------
function confirmTag(s) {
  return s === 'CONFIRMED' ? 'success' : (s === 'ESCATED' ? 'danger' : (s === 'CHANGE_PENDING' ? 'warning' : 'info'))
}
function confirmLabel(s) {
  return { PUSHED: '已推送', CONFIRMED: '已确认', CHANGE_PENDING: '改期待处理', ESCATED: '已升级' }[s] || (s || '未推送')
}
function actionLabel(t) {
  return { PUSHED: '下达推送', REMIND: '催办', ESCALATE: '升级', CONFIRM: '交期确认',
    CHANGE_REQUEST: '改期申请', CHANGE_ACCEPTED: '改期接受', LOCK: '锁定', UNLOCK: '解锁' }[t] || t
}
function timelineType(t) {
  if (t === 'LOCK') return 'danger'
  if (t === 'ESCALATE' || t === 'REMIND') return 'warning'
  if (t === 'CONFIRM' || t === 'CHANGE_ACCEPTED' || t === 'UNLOCK') return 'success'
  return 'primary'
}
function pct(v) {
  if (v == null) return '—'
  return (Number(v) * 100).toFixed(2) + '%'
}

function ensureRecon() {
  if (reconLoaded.value) return
  if (!canViewRecon.value) {
    reconDenied.value = true
    return
  }
  reconLoaded.value = true
  loadStatements()
}

onMounted(() => {
  loadPoCoops()
  loadAsns()
  loadAlerts()
  loadSettlements()
  if (tab.value === 'recon') ensureRecon()
})
</script>

<style scoped>
.page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }
.sub-title { font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 6px; }
.timeline-box { padding: 8px 16px; }
.tl-detail { color: #606266; font-size: 12px; }
</style>
