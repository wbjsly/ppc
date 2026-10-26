<template>
  <div class="ncr-page">
    <el-alert type="error" :closable="false" style="margin-bottom: 12px;"
      title="不合格品 NCR（2.5.3 / 6.5）：判不合格同事务生成并冻结，评审须选定处置，处置凭证齐全 → CAPA 有效 + 复检放行方可关闭"
      :description="tab === 'isolate'
        ? '6.5.1 标识隔离：冻结中的 NCR 隔离清单，行内隔离确认（货位/贴标人留痕）与标识卡打印。'
        : tab === 'dispose'
          ? '6.5.2 处置跟踪：默认展示全部在处置流单据（退货/挑选/返工/让步/报废中），处置阶段列推导下一步待办。'
          : '两阶段冻结：未过账冻 GR 行（阻断入库），已入库冻库存（AVAILABLE → QC）；评审时限 Critical 4h / Major 24h / Minor 48h。'" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 150px;" @change="load">
          <el-option v-for="(label, val) in STATUS_TEXT" :key="val" :label="label" :value="val" />
        </el-select>
        <el-select v-model="filters.severity" placeholder="严重度" clearable style="width: 130px;" @change="load">
          <el-option label="Critical" value="CRITICAL" />
          <el-option label="Major" value="MAJOR" />
          <el-option label="Minor" value="MINOR" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="NCR 号 / 批号 / 物料 / 供应商" clearable
          style="width: 240px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <!-- 6.1 漏检召回评估入口：针对已放行批次手工建 NCR -->
        <el-button v-if="canReview" type="danger" plain @click="openReleasedDialog">
          针对已放行批次创建 NCR
        </el-button>
        <span class="spacer" />
        <el-tag v-if="scopeText" type="warning" size="small">{{ scopeText }}</el-tag>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
        <el-table-column prop="ncrNo" label="NCR 号" width="150">
          <template #default="{ row }"><b>{{ row.ncrNo }}</b></template>
        </el-table-column>
        <el-table-column prop="lotNo" label="检验批" width="140" show-overflow-tooltip />
        <el-table-column prop="itemCode" label="物料" width="130" />
        <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column prop="supplierName" label="供应商" width="130" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column label="严重度" width="95" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ CRITICAL: 'danger', MAJOR: 'warning', MINOR: 'info' }[row.severity]">
              {{ row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ STATUS_TEXT[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处置" width="90" align="center">
          <template #default="{ row }">{{ DISP_TEXT[row.disposition] || '—' }}</template>
        </el-table-column>
        <!-- 6.5.2 处置阶段列（后端实时推导，design D4） -->
        <el-table-column v-if="tab === 'dispose'" label="处置阶段" width="150" align="center">
          <template #default="{ row }">
            <el-tooltip v-if="row.disposeStage" :content="row.disposeStageDetail || row.disposeStage" placement="top">
              <el-tag size="small" :type="stageType(row.disposeStage)">{{ row.disposeStage }}</el-tag>
            </el-tooltip>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="冻结" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.frozenFlag === '1'" type="danger" size="small">
              冻结·{{ SCOPE_TEXT[row.freezeScope] || row.freezeScope }}
            </el-tag>
            <el-tag v-else type="success" size="small">已解冻</el-tag>
          </template>
        </el-table-column>
        <!-- 6.5.1 隔离列 -->
        <el-table-column v-if="tab === 'isolate'" label="隔离" width="170" align="center">
          <template #default="{ row }">
            <template v-if="row.isolatedFlag === '1'">
              <el-tag type="success" size="small">已隔离</el-tag>
              <div class="bin-line">{{ row.isolateBin }} · {{ row.isolatedBy }}</div>
            </template>
            <el-tag v-else type="info" size="small">待隔离确认</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="评审时限" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'CREATED' && row.reviewDueTime">
              <span :style="{ color: row.reviewOverdue ? '#F56C6C' : '' , fontWeight: row.reviewOverdue ? 700 : 400 }">
                {{ reviewCountdown(row) }}
              </span>
              <el-tag v-if="row.dispoPendingFlag === '1'" type="warning" size="small" style="margin-left:4px;">待经理审批</el-tag>
              <el-tag v-else-if="row.reviewEscalatedFlag === '1'" type="danger" size="small" style="margin-left:4px;">已升级</el-tag>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="超期" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.overdueFlag === '1'" type="danger" size="small">30天+</el-tag>
            <el-tag v-else-if="row.perfFrozenFlag === '1'" type="danger" size="small">绩效冻结</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="tab === 'isolate' ? 200 : 80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <template v-if="tab === 'isolate'">
              <el-button v-if="row.frozenFlag === '1'" link type="warning"
                @click.stop="openIsolate(row)">隔离确认</el-button>
              <el-button link type="info" @click.stop="printCard(row)">标识卡</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" :title="detail.ncr ? `${detail.ncr.ncrNo} · ${STATUS_TEXT[detail.ncr.status] || detail.ncr.status}` : 'NCR 详情'"
      size="760px" destroy-on-close>
      <div v-if="detail.ncr" class="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="检验批">{{ detail.ncr.lotNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="严重度">
            <el-tag size="small" :type="{ CRITICAL: 'danger', MAJOR: 'warning', MINOR: 'info' }[detail.ncr.severity]">
              {{ detail.ncr.severity }}
            </el-tag>
            <span v-if="detail.ncr.ctqFlag === '1'" style="margin-left:6px;color:#F56C6C;">CTQ</span>
            <span v-if="detail.ncr.regulatoryFlag === '1'" style="margin-left:6px;color:#F56C6C;">安全/法规</span>
          </el-descriptions-item>
          <el-descriptions-item label="物料">{{ detail.ncr.itemCode }} {{ detail.ncr.itemName }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.ncr.supplierName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="不合格数量">{{ detail.ncr.qty }}</el-descriptions-item>
          <el-descriptions-item label="不合格项">{{ detail.ncr.defectItem || '—' }}</el-descriptions-item>
          <el-descriptions-item label="冻结">
            <el-tag v-if="detail.ncr.frozenFlag === '1'" type="danger" size="small">
              冻结中 · {{ SCOPE_TEXT[detail.ncr.freezeScope] || detail.ncr.freezeScope }}
            </el-tag>
            <el-tag v-else type="success" size="small">已解冻</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="隔离">
            <template v-if="detail.ncr.isolatedFlag === '1'">
              <el-tag type="success" size="small">已隔离</el-tag>
              <span style="margin-left:6px;">{{ detail.ncr.isolateBin }} · {{ detail.ncr.isolatedBy }} @ {{ detail.ncr.isolatedTime }}</span>
            </template>
            <el-tag v-else type="info" size="small">未确认</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="处置">{{ DISP_TEXT[detail.ncr.disposition] || '未评审' }}</el-descriptions-item>
          <el-descriptions-item label="评审时限" :span="2">
            <span :style="{ color: reviewOverdueNow() ? '#F56C6C' : '' }">{{ detail.ncr.reviewDueTime || '—' }}</span>
            <span v-if="detail.ncr.reviewBy" style="margin-left:10px;">
              评审人 {{ detail.ncr.reviewBy }} @ {{ detail.ncr.reviewTime }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="评审意见" :span="2">{{ detail.ncr.reviewOpinion || '—' }}</el-descriptions-item>
          <el-descriptions-item label="不合格描述" :span="2">{{ detail.ncr.defectDesc || '—' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 检验项 -->
        <h4 class="sec">检验项（{{ (detail.items || []).length }}）</h4>
        <el-table :data="detail.items || []" size="mini" border max-height="200">
          <el-table-column prop="sequenceNo" label="#" width="45" />
          <el-table-column prop="characteristicName" label="特性" width="120" />
          <el-table-column label="规格" width="150">
            <template #default="{ row }">
              <span v-if="row.specType === 'NUMERIC'">[{{ row.lowerLimit ?? '−∞' }}, {{ row.upperLimit ?? '+∞' }}]</span>
              <span v-else>定性</span>
              <el-tag v-if="row.ctqFlag === '1'" size="small" type="danger" style="margin-left:4px;">CTQ</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="measuredValue" label="实测" width="80" />
          <el-table-column label="判定" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ PASS: 'success', FAIL: 'danger', PENDING: 'info' }[row.judge]">
                {{ { PASS: '合格', FAIL: '不合格', PENDING: '待检' }[row.judge] || row.judge }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="100" />
        </el-table>

        <!-- 操作区（按角色显隐） -->
        <h4 class="sec">处置操作</h4>
        <!-- 6.2 漏检召回评估：仅「放行后发现」NCR（refType=RELEASED_LOT） -->
        <el-alert v-if="detail.ncr.refType === 'RELEASED_LOT'" type="error" :closable="false"
          style="margin-bottom:8px;" show-icon
          title="放行后发现不合格：可发起召回评估（创建追溯单双向关联；冻结等重动作由追溯流程决策）">
          <template #default>
            <el-button v-if="detail.recallTraceNo" type="warning" size="small" style="margin-top:6px;"
              @click="goTrace(detail.recallTraceId)">
              已关联追溯单 {{ detail.recallTraceNo }}（查看）
            </el-button>
            <el-button v-else-if="canReview" type="danger" size="small" style="margin-top:6px;"
              :loading="recallLoading" @click="doRecallEval">
              发起召回评估
            </el-button>
          </template>
        </el-alert>
        <el-alert v-if="detail.ncr.dispoPendingFlag === '1'" type="warning" :closable="false" style="margin-bottom:8px;"
          :title="`CTQ 处置待质量经理审批（${DISP_TEXT[detail.ncr.disposition] || detail.ncr.disposition}），审批通过后自动流转；驳回可改选`" />
        <el-alert v-else-if="detail.ncr.status === 'CREATED'" type="warning" :closable="false" style="margin-bottom:8px;"
          :title="`评审必须选定处置方式（剩余 ${reviewCountdown(detail.ncr)}）${detail.ncr.ctqFlag === '1' ? '；CTQ 单提交后须质量经理审批通过才流转' : ''}`" />

        <div v-if="canReview && detail.ncr.status === 'CREATED' && detail.ncr.dispoPendingFlag !== '1'" class="op-block">
          <el-radio-group v-model="reviewForm.disposition">
            <el-radio-button value="RETURN">退货</el-radio-button>
            <el-radio-button value="SORT">挑选</el-radio-button>
            <el-radio-button value="REWORK">返工</el-radio-button>
            <el-radio-button value="CONCESSION"
              :disabled="detail.ncr.regulatoryFlag === '1'"
              :title="detail.ncr.regulatoryFlag === '1' ? '安全/法规 CTQ 禁止让步（BR-4.12-26）' : ''">
              让步接收
            </el-radio-button>
            <el-radio-button value="SCRAP"
              :disabled="detail.ncr.regulatoryFlag === '1'"
              :title="detail.ncr.regulatoryFlag === '1' ? '安全/法规 CTQ 仅可退货或返工（BR-4.12-26）' : ''">
              报废
            </el-radio-button>
          </el-radio-group>
          <div v-if="detail.ncr.regulatoryFlag === '1'" style="color:#F56C6C;font-size:12px;margin:6px 0;">
            安全/法规 CTQ 不合格：仅可退货或返工，禁止让步接收与报废（BR-4.12-26）
          </div>
          <el-input v-model="reviewForm.opinion" type="textarea" :rows="2" placeholder="评审意见（必填，至少 2 字）"
            style="margin-top:8px;" />
          <el-button type="primary" size="small" style="margin-top:8px;" @click="doReview">
            {{ detail.ncr.ctqFlag === '1' ? '提交评审（转质量经理审批）' : '提交评审' }}
          </el-button>
          <el-button size="small" @click="showCancel = true">作废</el-button>
        </div>

        <div v-if="canDispose && ['SORTING', 'REWORKING'].includes(detail.ncr.status)" class="op-block">
          <el-input v-model="planForm.plan" type="textarea" :rows="2"
            :placeholder="detail.ncr.disposition === 'SORT' ? '挑选方案（范围/责任方/隔离区）' : '返工方案概述（方式/责任方/完成时间）'" />
          <!-- 返工结构化三字段 + CTQ 全含勾选（spec MODIFIED） -->
          <template v-if="detail.ncr.disposition === 'REWORK'">
            <el-input v-model="planForm.reworkSteps" type="textarea" :rows="2" placeholder="返工工序步骤（必填，如：拆解→换轴→复测）"
              style="margin-top:8px;" />
            <el-input v-model="planForm.reworkParams" type="textarea" :rows="2" placeholder="关键工艺参数（必填，如：扭矩 3.2±0.2N·m）"
              style="margin-top:8px;" />
            <el-input v-model="planForm.reworkCheckReq" type="textarea" :rows="2" placeholder="返工后检验要求（必填，至少包含原检验标准全部 CTQ 项）"
              style="margin-top:8px;" />
            <el-checkbox v-model="planForm.ctqConfirmed" style="margin-top:8px;">
              已确认返工检验标准包含原检验标准全部 CTQ 项
            </el-checkbox>
          </template>
          <el-button type="primary" size="small" style="margin-top:8px;" @click="doPlan">保存方案</el-button>
          <el-divider style="margin:10px 0;" />

          <!-- 挑选结果结构化四字段（spec MODIFIED：合计校验 + 低合格率软提示） -->
          <template v-if="detail.ncr.disposition === 'SORT'">
            <div class="sort-grid">
              <el-input-number v-model="sortForm.total" :min="0" :precision="2" placeholder="总数量" size="small" />
              <el-input-number v-model="sortForm.ok" :min="0" :precision="2" placeholder="合格数量" size="small" />
              <el-input-number v-model="sortForm.ng" :min="0" :precision="2" placeholder="不合格数量" size="small" />
              <el-input-number v-model="sortForm.hours" :min="0" :precision="2" placeholder="挑选工时(h)" size="small" />
            </div>
            <div v-if="sortBalanceError" class="warn-line">{{ sortBalanceError }}</div>
            <div v-else-if="sortLowYield" class="warn-line">
              挑选合格率 {{ sortYield }}%，低于 50%——规格建议转退货处置（不阻断，确认后可继续）
            </div>
            <el-input-number v-model="costForm.amount" :min="0" :precision="2" placeholder="处置成本（元，可选，归集 COPQ）"
              size="small" style="margin-top:8px;width:260px;" />
          </template>
          <el-input v-model="planForm.result" type="textarea" :rows="2" style="margin-top:8px;"
            :placeholder="detail.ncr.disposition === 'SORT' ? '挑选执行凭证（必填：处理去向说明）' : '执行结果凭证（必填：返工批号/完成时间）'" />
          <el-button type="success" size="small" style="margin-top:8px;" @click="doConfirm">
            确认处置完成（生成复检批）
          </el-button>
        </div>

        <el-alert v-if="detail.ncr.status === 'RETURNING'" type="info" :closable="false" style="margin-bottom:8px;"
          title="退货处置：待 2.6.1 质量退货自动带出退货单（PO/收货单/数量/PO 单价），审批后仓库出库" />

        <el-alert v-if="detail.ncr.status === 'CONCESSION'" type="info" :closable="false" style="margin-bottom:8px;"
          title="让步接收处置：在 2.5.2 发起申请，双签（质量经理 + 技术负责人）批准后方可确认处置" />

        <el-alert v-if="detail.ncr.status === 'SCRAPPING'" type="warning" :closable="false" style="margin-bottom:8px;"
          title="报废处置：先在 4.5.4 报废出库创建报废单（原因=质量、关联本 NCR）完成会签过账，再回填报废单号确认处置" />
        <div v-if="canDispose && detail.ncr.status === 'SCRAPPING'" class="op-block">
          <el-input v-model="planForm.result" type="textarea" :rows="2" placeholder="报废执行凭证（必填：4.5.4 关联报废单号）" />
          <el-button type="success" size="small" style="margin-top:8px;" @click="doConfirm">
            确认处置完成（凭报废单号）
          </el-button>
        </div>

        <div v-if="canDispose && detail.ncr.status === 'DISPOSED'" class="op-block">
          <el-alert type="warning" :closable="false" style="margin-bottom:8px;"
            title="关闭校验：CAPA 有效性须验证通过（VALID + CLOSED）；挑选/返工须复检批已放行" />
          <el-input v-model="closeForm.opinion" type="textarea" :rows="2" placeholder="关闭意见（复检结论等）" />
          <el-button type="success" size="small" style="margin-top:8px;" @click="doClose">关闭 NCR（解冻归档）</el-button>
        </div>

        <!-- 结构化结果展示（挑选/返工已确认后） -->
        <template v-if="detail.ncr.sortTotalQty != null">
          <h4 class="sec">挑选结果（结构化）</h4>
          <el-descriptions :column="4" border size="small">
            <el-descriptions-item label="总数量">{{ detail.ncr.sortTotalQty }}</el-descriptions-item>
            <el-descriptions-item label="合格">{{ detail.ncr.sortOkQty }}</el-descriptions-item>
            <el-descriptions-item label="不合格">{{ detail.ncr.sortNgQty }}</el-descriptions-item>
            <el-descriptions-item label="工时(h)">{{ detail.ncr.sortHours ?? '—' }}</el-descriptions-item>
          </el-descriptions>
        </template>
        <template v-if="detail.ncr.disposeCostAmount != null">
          <h4 class="sec">处置成本</h4>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="COPQ 内部失败">{{ detail.ncr.disposeCostAmount }} 元</el-descriptions-item>
          </el-descriptions>
        </template>

        <!-- CAPA / 复检批 -->
        <template v-if="detail.capa">
          <h4 class="sec">关联 CAPA</h4>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="编号">{{ detail.capa.capaNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ detail.capa.status }}</el-descriptions-item>
            <el-descriptions-item label="有效性">
              <el-tag size="small" :type="{ VALID: 'success', INVALID: 'danger', PARTIAL: 'warning' }[detail.capa.verifyResult] || 'info'">
                {{ detail.capa.verifyResult }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </template>

        <template v-if="(detail.recheckLots || []).length">
          <h4 class="sec">复检批</h4>
          <el-table :data="detail.recheckLots" size="mini" border>
            <el-table-column prop="lotNo" label="批号" width="150" />
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'RELEASED' ? 'success' : 'primary'">
                  {{ { PENDING: '待检', INPUTTING: '待确认', RELEASED: '已放行', BLOCKED: '无标准' }[row.status] || row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="releasedDate" label="放行时间" min-width="150" />
          </el-table>
        </template>

        <!-- 日志时间线 -->
        <h4 class="sec">操作与升级日志</h4>
        <el-timeline style="padding-left: 4px;">
          <el-timeline-item v-for="l in (detail.logs || [])" :key="l.id"
            :timestamp="l.createDate" placement="top"
            :type="LOG_TONE[l.action] || 'primary'">
            <b>{{ LOG_TEXT[l.action] || l.action }}</b>
            <span v-if="l.notifyRole" style="color:#E6A23C;margin-left:6px;">→ {{ ROLE_TEXT[l.notifyRole] || l.notifyRole }}</span>
            <span v-if="l.ccRole" style="color:#909399;margin-left:4px;">抄送 {{ ROLE_TEXT[l.ccRole] || l.ccRole }}</span>
            <div style="color:#606266;font-size:12px;">{{ l.detail }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 6.1 针对已放行批次创建 NCR（漏检召回评估入口） -->
    <el-dialog v-model="showReleased" title="针对已放行批次创建 NCR（放行后发现）" width="560px"
      @open="loadReleasedLots">
      <el-form label-width="90px" size="small">
        <el-form-item label="已放行批次" required>
          <el-select v-model="releasedForm.lotId" filterable placeholder="选择 RELEASED 检验批（批号/物料）"
            style="width:100%;" :loading="releasedLoading">
            <el-option v-for="l in releasedLots" :key="l.id"
              :label="`${l.lotNo} · ${l.itemCode} ${l.itemName} · ${l.batchNo || '无批号'}`" :value="l.id" />
          </el-select>
          <div v-if="!releasedLots.length && !releasedLoading" class="warn-line">
            暂无已放行（RELEASED）批次——先完成判定放行再来建单
          </div>
        </el-form-item>
        <el-form-item label="不合格项" required>
          <el-input v-model="releasedForm.defectItem" placeholder="如：外观划伤 / 尺寸超差" maxlength="100" />
        </el-form-item>
        <el-form-item label="缺陷描述" required>
          <el-input v-model="releasedForm.defectDesc" type="textarea" :rows="3"
            placeholder="放行后发现的漏检/误判情况（系统将自动标记「放行后发现」）" />
        </el-form-item>
        <el-form-item label="严重度">
          <el-radio-group v-model="releasedForm.severity">
            <el-radio-button value="MINOR">Minor</el-radio-button>
            <el-radio-button value="MAJOR">Major</el-radio-button>
            <el-radio-button value="CRITICAL">Critical</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="CTQ 项">
          <el-checkbox v-model="releasedForm.ctq">
            缺陷涉及 CTQ 特性（CTQ 单评审须质量经理审批）
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showReleased = false">取消</el-button>
        <el-button type="danger" :loading="releasedSubmitting" @click="doCreateReleased">创建 NCR（冻结）</el-button>
      </template>
    </el-dialog>

    <!-- 6.5.1 隔离确认弹窗 -->
    <el-dialog v-model="showIsolate" title="不合格品隔离确认" width="440px">
      <div v-if="isolateTarget" style="margin-bottom:10px;">
        <b>{{ isolateTarget.ncrNo }}</b> · {{ isolateTarget.itemCode }} {{ isolateTarget.itemName }}<br />
        批号 {{ isolateTarget.lotNo || '—' }} · 数量 {{ isolateTarget.qty }} ·
        冻结 {{ SCOPE_TEXT[isolateTarget.freezeScope] || isolateTarget.freezeScope }}
      </div>
      <el-input v-model="isolateBin" placeholder="隔离区货位（必填，如：Q-HOLD-01）" />
      <template #footer>
        <el-button @click="showIsolate = false">取消</el-button>
        <el-button type="warning" @click="doIsolate">确认隔离（贴标）</el-button>
      </template>
    </el-dialog>

    <!-- 6.5.1 标识卡打印（浏览器打印模板，无单据编号——偏差 D-2） -->
    <div v-if="cardRow" id="ncr-label-card" class="label-card">
      <div class="label-title">不合格品标识卡</div>
      <table>
        <tr><td class="k">NCR 号</td><td class="v"><b>{{ cardRow.ncrNo }}</b></td></tr>
        <tr><td class="k">物料</td><td class="v">{{ cardRow.itemCode }} {{ cardRow.itemName }}</td></tr>
        <tr><td class="k">批号</td><td class="v">{{ cardRow.lotNo || '—' }}</td></tr>
        <tr><td class="k">不合格数量</td><td class="v">{{ cardRow.qty }}</td></tr>
        <tr><td class="k">严重度</td><td class="v">{{ cardRow.severity }}</td></tr>
        <tr><td class="k">不合格项</td><td class="v">{{ cardRow.defectItem || cardRow.defectDesc || '—' }}</td></tr>
        <tr><td class="k">冻结形态</td><td class="v">{{ SCOPE_TEXT[cardRow.freezeScope] || cardRow.freezeScope || '—' }}</td></tr>
        <tr><td class="k">隔离状态</td><td class="v">
          {{ cardRow.isolatedFlag === '1' ? `已隔离（${cardRow.isolateBin} · ${cardRow.isolatedBy}）` : '待隔离确认' }}
        </td></tr>
        <tr><td class="k">日期</td><td class="v">{{ new Date().toLocaleDateString() }}</td></tr>
      </table>
      <div class="label-foot">红色标签 · 隔离存放 · 未经质量放行不得使用</div>
    </div>

    <!-- 作废原因 -->
    <el-dialog v-model="showCancel" title="作废 NCR（须填原因）" width="440px">
      <el-input v-model="cancelReason" type="textarea" :rows="3" placeholder="作废原因（至少 2 字）" />
      <template #footer>
        <el-button @click="showCancel = false">取消</el-button>
        <el-button type="danger" @click="doCancel">确认作废</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getNcrPageApi, getNcrDetailApi, reviewNcrApi, planNcrApi,
  confirmNcrApi, closeNcrApi, cancelNcrApi, isolateNcrApi,
  createReleasedLotNcrApi, recallEvaluationApi
} from '@/api/qms/ncr'
import { getLotPageApi } from '@/api/qms/inspection'

const route = useRoute()
const userStore = useUserStore()

const STATUS_TEXT = {
  CREATED: '待评审', REVIEWING: '评审中', RETURNING: '退货中', SORTING: '挑选中',
  REWORKING: '返工中', CONCESSION: '让步中', DISPOSED: '已处置', CLOSED: '已关闭', CANCELLED: '已作废'
}
const DISP_TEXT = { RETURN: '退货', SORT: '挑选', REWORK: '返工', CONCESSION: '让步接收', SCRAP: '报废' }
const SCOPE_TEXT = { GR_LINE: 'GR 行', STOCK: '库存', BOTH: '双冻结', NONE: '无对象', WORKORDER: '工单' }
const ROLE_TEXT = {
  ROLE_QUALITY_MGR: '质量经理', ROLE_QUALITY_DIRECTOR: '质量总监', ROLE_PM: '采购经理',
  ROLE_TECH_OWNER: '技术负责人', ROLE_QUALITY_ENG: '质量工程师'
}
const LOG_TEXT = {
  CREATE: 'NCR 生成', CAPA: 'CAPA 自动立项', REVIEW: '评审', REVIEW_SUBMIT: '评审提交审批',
  REVIEW_APPROVE: '处置审批通过', REVIEW_REJECT: '处置审批驳回', DISPOSE: '方案录入', CONFIRM: '处置确认',
  RECHECK: '复检批生成', CLOSE: '关闭', CANCEL: '作废', UNFREEZE: '解冻', ISOLATE: '隔离确认',
  ESCALATE_MGR: '超时升级', ESCALATE_DIRECTOR: '超期升级', REMIND: '超期提醒'
}
const LOG_TONE = {
  CREATE: 'primary', CAPA: 'primary', ESCALATE_MGR: 'warning', ESCALATE_DIRECTOR: 'danger',
  REMIND: 'warning', CLOSE: 'success', CANCEL: 'info', UNFREEZE: 'success',
  ISOLATE: 'warning', REVIEW_SUBMIT: 'warning', REVIEW_REJECT: 'danger', REVIEW_APPROVE: 'success'
}
// 6.5.2 处置阶段 → 标签色
const STAGE_TONE = {
  '待退货出库': 'warning', '退货出库完成待确认': 'primary', '待方案录入': 'warning',
  '待处置确认': 'primary', '待复检放行': 'warning', '复检已放行待关闭': 'success',
  '待双签批准': 'warning', '双签完成待确认': 'primary', '待回填报废单号': 'danger',
  '处置待质量经理审批': 'warning'
}
// 6.5.2 默认在途五态
const DISPOSE_INFLOW = 'RETURNING,SORTING,REWORKING,CONCESSION,SCRAPPING'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const tab = route.meta.tab || 'all'
const filters = reactive({ status: '', severity: '', keyword: '', current: 1, size: 10 })

const scopeText = computed(() => ({
  isolate: '6.5.1 标识隔离：默认只看冻结中的批次',
  dispose: '6.5.2 处置跟踪：默认展示在处置流全部单据（退货/挑选/返工/让步/报废中）'
}[tab] || ''))

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const hasRole = (...want) =>
  myRoles.value.some(r => r === 'ROLE_ADMIN' || want.includes(r))
const canReview = computed(() => hasRole('ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR'))
const canDispose = computed(() => hasRole('ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR', 'ROLE_QUALITY_DIRECTOR'))

async function load() {
  loading.value = true
  try {
    const params = {
      current: filters.current, size: filters.size,
      severity: filters.severity || undefined,
      keyword: filters.keyword || undefined
    }
    if (tab === 'isolate') {
      // 隔离清单：冻结中且非作废/关闭（后端 frozenOnly 过滤），显式状态选择时仍生效
      params.frozenOnly = true
      if (filters.status) params.status = filters.status
    } else if (tab === 'dispose') {
      // 多态在途队列：用户未显式选状态时用五态 in 查询；显式选择则按选择查历史
      params.withStage = true
      if (filters.status) params.status = filters.status
      else params.statuses = DISPOSE_INFLOW
    } else {
      if (filters.status) params.status = filters.status
    }
    const res = await getNcrPageApi(params)
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}

function statusType(s) {
  return {
    CREATED: 'danger', RETURNING: 'warning', SORTING: 'warning', REWORKING: 'warning',
    CONCESSION: 'warning', DISPOSED: 'primary', CLOSED: 'success', CANCELLED: 'info'
  }[s] || 'info'
}

function stageType(stage) {
  return STAGE_TONE[stage] || 'info'
}

function reviewCountdown(row) {
  if (!row.reviewDueTime) return '—'
  const diff = new Date(row.reviewDueTime.replace(' ', 'T')) - Date.now()
  const h = Math.floor(Math.abs(diff) / 3600000)
  const m = Math.floor((Math.abs(diff) % 3600000) / 60000)
  const text = `${h}h${m}m`
  return diff < 0 ? `超时 ${text}` : `剩 ${text}`
}

function reviewOverdueNow() {
  const n = detail.value.ncr
  return n && n.status === 'CREATED' && n.reviewDueTime &&
    new Date(n.reviewDueTime.replace(' ', 'T')) < Date.now()
}

// ---------- 详情 ----------
const drawer = ref(false)
const detail = ref({})
const reviewForm = reactive({ disposition: '', opinion: '' })
const planForm = reactive({ plan: '', result: '', reworkSteps: '', reworkParams: '', reworkCheckReq: '', ctqConfirmed: false })
const sortForm = reactive({ total: null, ok: null, ng: null, hours: null })
const costForm = reactive({ amount: null })
const showCancel = ref(false)
const cancelReason = ref('')

// 挑选合计校验（前端同步；后端最终校验）
const sortBalanceError = computed(() => {
  const { total: t, ok, ng } = sortForm
  if (t == null || ok == null || ng == null) return ''
  if (ok + ng !== t) return `数量不平：合格 ${ok} + 不合格 ${ng} ≠ 总数量 ${t}`
  return ''
})
const sortYield = computed(() => {
  const { total: t, ok } = sortForm
  if (!t || ok == null) return null
  return Math.round((ok / t) * 100)
})
const sortLowYield = computed(() => sortYield.value != null && sortYield.value < 50)

async function openDetail(row) {
  const res = await getNcrDetailApi(row.id)
  detail.value = res.data || {}
  reviewForm.disposition = ''
  reviewForm.opinion = ''
  const n = res.data && res.data.ncr
  planForm.plan = (n && n.disposePlan) || ''
  planForm.result = ''
  planForm.reworkSteps = (n && n.reworkSteps) || ''
  planForm.reworkParams = (n && n.reworkParams) || ''
  planForm.reworkCheckReq = (n && n.reworkCheckReq) || ''
  planForm.ctqConfirmed = !!(n && n.reworkCtqConfirmed === '1')
  sortForm.total = n && n.sortTotalQty != null ? Number(n.sortTotalQty) : null
  sortForm.ok = n && n.sortOkQty != null ? Number(n.sortOkQty) : null
  sortForm.ng = n && n.sortNgQty != null ? Number(n.sortNgQty) : null
  sortForm.hours = n && n.sortHours != null ? Number(n.sortHours) : null
  costForm.amount = n && n.disposeCostAmount != null ? Number(n.disposeCostAmount) : null
  cancelReason.value = ''
  drawer.value = true
}

async function doReview() {
  if (!reviewForm.disposition) return ElMessage.warning('请选择处置方式')
  try {
    await reviewNcrApi(detail.value.ncr.id, reviewForm.disposition, reviewForm.opinion)
    const isCtq = detail.value.ncr.ctqFlag === '1'
    ElMessage.success(isCtq ? '已提交，待质量经理审批后流转' : '评审完成，已进入处置流')
    openDetail(detail.value.ncr)
    load()
  } catch { /* 拦截器已统一弹错 */ }
}

async function doPlan() {
  try {
    const rework = detail.value.ncr.disposition === 'REWORK'
      ? {
          steps: planForm.reworkSteps,
          params: planForm.reworkParams,
          checkReq: planForm.reworkCheckReq,
          ctqConfirmed: planForm.ctqConfirmed
        }
      : {}
    await planNcrApi(detail.value.ncr.id, planForm.plan, rework)
    ElMessage.success('方案已保存')
    openDetail(detail.value.ncr)
  } catch { /* 拦截器已统一弹错 */ }
}

async function doConfirm() {
  const isSort = detail.value.ncr.disposition === 'SORT'
  if (isSort && (sortForm.total == null || sortForm.ok == null || sortForm.ng == null || sortForm.hours == null)) {
    return ElMessage.warning('挑选结果四字段必填（总数量/合格/不合格/工时）')
  }
  if (isSort && sortBalanceError.value) {
    return ElMessage.error(sortBalanceError.value)
  }
  try {
    const isScrap = detail.value.ncr.disposition === 'SCRAP'
    await ElMessageBox.confirm(
      isScrap ? '确认报废处置完成？将凭报废单号归档本 NCR 处置。' : '确认处置执行完成？挑选/返工将同事务生成复检批。',
      '处置确认', { type: 'warning' })
  } catch { return }
  try {
    const res = await confirmNcrApi(detail.value.ncr.id, planForm.result, isSort
      ? {
          sortTotalQty: sortForm.total, sortOkQty: sortForm.ok,
          sortNgQty: sortForm.ng, sortHours: sortForm.hours,
          costAmount: costForm.amount
        }
      : {})
    const data = res.data || {}
    const warnings = data.warnings || []
    if (warnings.length) {
      // 软提示（如低合格率）：黄色确认，不阻断
      warnings.forEach(w => ElMessage.warning(w))
    } else {
      ElMessage.success(detail.value.ncr.disposition === 'SCRAP' ? '处置已确认' : '处置已确认，复检批已生成')
    }
    openDetail(detail.value.ncr)
    load()
  } catch { /* 拦截器已统一弹错 */ }
}

async function doClose() {
  try {
    await ElMessageBox.confirm('关闭后 NCR 只读归档、库存解冻。确认关闭？', '关闭 NCR', { type: 'warning' })
  } catch { return }
  try {
    await closeNcrApi(detail.value.ncr.id, closeForm.opinion)
    ElMessage.success('NCR 已关闭并解冻')
    openDetail(detail.value.ncr)
    load()
  } catch { /* 拦截器已统一弹错 */ }
}

async function doCancel() {
  try {
    await cancelNcrApi(detail.value.ncr.id, cancelReason.value)
    ElMessage.success('已作废并解冻')
    showCancel.value = false
    openDetail(detail.value.ncr)
    load()
  } catch { /* 拦截器已统一弹错 */ }
}

// ---------- 6.5.1 隔离确认 + 标识卡 ----------
const showIsolate = ref(false)
const isolateTarget = ref(null)
const isolateBin = ref('')

function openIsolate(row) {
  isolateTarget.value = row
  isolateBin.value = row.isolateBin || ''
  showIsolate.value = true
}

async function doIsolate() {
  try {
    await isolateNcrApi(isolateTarget.value.id, isolateBin.value)
    ElMessage.success('隔离确认完成（已留痕）')
    showIsolate.value = false
    load()
    if (drawer.value && detail.value.ncr && detail.value.ncr.id === isolateTarget.value.id) {
      openDetail(isolateTarget.value)
    }
  } catch { /* 拦截器已统一弹错 */ }
}

// 标识卡：取详情数据（含不合格项描述），浏览器打印
const cardRow = ref(null)
async function printCard(row) {
  const res = await getNcrDetailApi(row.id)
  const n = (res.data && res.data.ncr) || row
  cardRow.value = { ...n, defectItem: n.defectItem || n.defectDesc || '' }
  // 等 DOM 渲染后打印
  setTimeout(() => {
    const original = document.title
    document.title = `标识卡-${row.ncrNo}`
    window.print()
    document.title = original
    cardRow.value = null
  }, 200)
}

// ---------- 6.1 针对已放行批次创建 NCR（漏检召回评估入口） ----------
const showReleased = ref(false)
const releasedLoading = ref(false)
const releasedSubmitting = ref(false)
const releasedLots = ref([])
const releasedForm = reactive({ lotId: '', defectItem: '', defectDesc: '', severity: 'MAJOR', ctq: false })

async function loadReleasedLots() {
  releasedLoading.value = true
  try {
    const res = await getLotPageApi({ status: 'RELEASED', current: 1, size: 50 })
    releasedLots.value = (res.data && res.data.records) || []
  } finally {
    releasedLoading.value = false
  }
}

function openReleasedDialog() {
  releasedForm.lotId = ''
  releasedForm.defectItem = ''
  releasedForm.defectDesc = ''
  releasedForm.severity = 'MAJOR'
  releasedForm.ctq = false
  showReleased.value = true
}

async function doCreateReleased() {
  if (!releasedForm.lotId) return ElMessage.warning('请选择已放行批次')
  if (!releasedForm.defectItem.trim()) return ElMessage.warning('不合格项必填')
  if (releasedForm.defectDesc.trim().length < 2) return ElMessage.warning('缺陷描述必填（至少 2 字）')
  releasedSubmitting.value = true
  try {
    const res = await createReleasedLotNcrApi({
      lotId: releasedForm.lotId,
      defectItem: releasedForm.defectItem,
      defectDesc: releasedForm.defectDesc,
      severity: releasedForm.severity,
      ctqFlag: releasedForm.ctq ? '1' : '0'
    })
    const ncr = res.data || {}
    ElMessage.success(`NCR ${ncr.ncrNo || ''} 已创建（放行后发现，已冻结）`)
    showReleased.value = false
    load()
    // 直接打开详情便于继续处置/发起评估
    openDetail(ncr)
  } catch { /* 拦截器已统一弹错 */ } finally {
    releasedSubmitting.value = false
  }
}

// ---------- 6.2 发起召回评估（仅 RELEASED_LOT NCR） ----------
const recallLoading = ref(false)

async function doRecallEval() {
  try {
    await ElMessageBox.confirm(
      '将为该批次创建追溯单并双向关联本 NCR（不自动执行冻结，冻结等重动作由追溯流程按流向分析决策）。是否继续？',
      '发起召回评估', { type: 'warning' })
  } catch { return }
  recallLoading.value = true
  try {
    const res = await recallEvaluationApi(detail.value.ncr.id)
    const out = res.data || {}
    ElMessage.success(out.existing
      ? `该批次已有未结案追溯单 ${out.traceNo}（已关联，不重复建单）`
      : `追溯单 ${out.traceNo} 已创建并关联本 NCR`)
    await openDetail(detail.value.ncr)
    load()
  } catch { /* 拦截器已统一弹错 */ } finally {
    recallLoading.value = false
  }
}

function goTrace(traceId) {
  if (traceId) window.open(`/m/4.13.1?traceId=${encodeURIComponent(traceId)}`, '_blank')
}

const closeForm = reactive({ opinion: '' })

onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.getUserInfo() } catch { /* ignore */ }
  }
  load()
})
</script>

<style scoped>
.ncr-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sec { margin: 16px 0 8px; font-size: 14px; color: #303133; }
.op-block { background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px; padding: 12px; }
.bin-line { font-size: 11px; color: #909399; margin-top: 2px; }
.warn-line { color: #E6A23C; font-size: 12px; margin-top: 6px; }
.sort-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 8px; }

/* 标识卡打印模板（仅打印时可见） */
.label-card { display: none; }
@media print {
  body * { visibility: hidden; }
  #ncr-label-card, #ncr-label-card * { visibility: visible; }
  #ncr-label-card { display: block; position: absolute; left: 0; top: 0; width: 100%; padding: 20px; }
  .label-title { font-size: 18px; font-weight: 700; text-align: center; margin-bottom: 12px; }
  .label-card table { width: 100%; border-collapse: collapse; }
  .label-card td { border: 1px solid #000; padding: 8px 10px; font-size: 14px; }
  .label-card .k { width: 110px; background: #f5f5f5; font-weight: 700; }
  .label-foot { margin-top: 12px; text-align: center; color: #F56C6C; font-weight: 700; font-size: 13px; }
}
</style>
