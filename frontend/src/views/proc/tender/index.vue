<template>
  <div class="tender-bidding">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">招标竞价</span>
          <div class="header-actions">
            <el-select v-model="listQuery.status" placeholder="状态" clearable style="width: 160px;" @change="loadList(1)">
              <el-option v-for="(n, v) in statusNames" :key="v" :label="n" :value="v" />
            </el-select>
            <el-input v-model="listQuery.keyword" placeholder="招标编号/名称" clearable style="width: 180px;" @keyup.enter="loadList(1)" />
            <el-button type="primary" @click="loadList(1)">查询</el-button>
            <el-button v-if="isAdmin" type="primary" :icon="Plus" @click="openCreate">创建招标项目</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="招标竞价（FR-4.2-10-1/2/3）：立项 → 报名与资格审查 → 多轮报价 → 截止锁价 → 开标评标 → 定标审批 → 公示 → 自动生成框架协议。"
        description="多轮报价只降不升、历史轮次只读（BR-4.2-06）；合格投标方 < MIN_QUOTE_COUNT（默认 3）阻断开标（BR-4.2-45）；评委评分提交后不可直接修改（BR-4.2-47）；公示期 3 个工作日，有效异议暂停协议生成（BR-4.2-48）；超招标门槛品类强制走招标（BR-4.2-44）。" />

      <el-tabs v-model="activeTab">
        <!-- ================= Tab1 招标列表 ================= -->
        <el-tab-pane label="招标项目" name="list">
          <el-table :data="rows" v-loading="loading" stripe>
            <el-table-column prop="tenderNo" label="招标编号" width="165">
              <template #default="{ row }"><b>{{ row.tenderNo }}</b></template>
            </el-table-column>
            <el-table-column prop="title" label="名称" min-width="160" show-overflow-tooltip />
            <el-table-column label="类型" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.tenderType === 'INVITE' ? 'warning' : 'info'">
                  {{ row.tenderType === 'INVITE' ? '邀请' : '公开' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="评标办法" width="90" align="center">
              <template #default="{ row }">{{ row.evalMethod === 'LOWEST' ? '最低价' : '综合评分' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
                <el-tag v-if="row.invitePending === '1'" type="warning" size="small" style="margin-left:2px;">待批</el-tag>
                <el-tag v-if="row.anomalyFlag === '1'" type="danger" size="small" style="margin-left:2px;">冻结</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="regDeadline" label="报名截止" width="150" />
            <el-table-column prop="quoteDeadline" label="报价截止" width="150" />
            <el-table-column label="合格" width="70" align="center">
              <template #default="{ row }">{{ row.qualifiedCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="报价轮次" width="80" align="center">
              <template #default="{ row }">{{ row.quoteCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
            :total="total" :page-size="listQuery.size" :current-change="loadList" />
        </el-tab-pane>

        <!-- ================= Tab2 我的评标 ================= -->
        <el-tab-pane label="我的评标" name="myjudge">
          <el-alert type="warning" :closable="false" style="margin-bottom: 12px;"
            title="仅显示分配给当前账号的评标任务（ROLE_BID_JUDGE）"
            description="四维评分按招标权重快照加权；提交后锁定，如需更正须走合规审批（BR-4.2-47）。" />
          <div style="margin-bottom: 12px;">
            <span style="font-size: 12px; color: #909399; margin-right: 8px;">评标中的招标：</span>
            <el-radio-group v-model="myTenderId" size="small" @change="loadMyTasks">
              <el-radio-button v-for="r in evaluatingTenders" :key="r.id" :value="r.id">
                {{ r.tenderNo }} {{ r.title }}
              </el-radio-button>
            </el-radio-group>
            <span v-if="!evaluatingTenders.length" style="font-size: 12px; color: #909399;">
              暂无处于「评标中」的招标项目
            </span>
          </div>
          <el-alert v-if="myMsg" type="error" show-icon :closable="false" :title="myMsg" style="margin-bottom: 10px;" />
          <el-table v-if="myTasks.length" :data="myTasks" v-loading="myLoading" stripe>
            <el-table-column prop="supplierName" label="投标方" min-width="150" />
            <el-table-column label="价格(40)" width="80" align="center">
              <template #default="{ row }">{{ row.scorePrice ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="交付(25)" width="80" align="center">
              <template #default="{ row }">{{ row.scoreDelivery ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="质量(25)" width="80" align="center">
              <template #default="{ row }">{{ row.scoreQuality ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="配合(10)" width="80" align="center">
              <template #default="{ row }">{{ row.scoreCooperation ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="加权得分" width="100" align="center">
              <template #default="{ row }"><b>{{ row.weightedScore ?? '—' }}</b></template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.scored ? 'success' : 'info'" size="small">{{ row.scored ? '已提交' : '未评分' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="row.scored" @click="openScore(row)">评分</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ================= Tab3 定标审批 ================= -->
        <el-tab-pane v-if="isAdmin" label="定标审批" name="approval">
          <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
            title="定标审批待办（design D6：招标独立单节点，采购总监）"
            description="通过后进入公示（默认 3 个工作日）并自动计算公示起止；驳回则回到评标。" />
          <el-table :data="todoRows" v-loading="todoLoading" stripe>
            <el-table-column prop="tenderNo" label="招标编号" width="165">
              <template #default="{ row }"><b>{{ row.tenderNo }}</b></template>
            </el-table-column>
            <el-table-column prop="title" label="名称" min-width="150" show-overflow-tooltip />
            <el-table-column prop="awardSupplierName" label="拟中标方" width="140" />
            <el-table-column prop="amount" label="中标价" width="110" align="right" />
            <el-table-column prop="awardScore" label="汇总得分" width="100" align="center" />
            <el-table-column label="路由链" width="110">
              <template #default="{ row }">
                <el-tag size="small">{{ row.route }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetailById(row.tenderId)">详情</el-button>
                <el-button link type="success" @click="onAwardApprove(row, true)">通过</el-button>
                <el-button link type="danger" @click="onAwardApprove(row, false)">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- ================= 创建招标项目 ================= -->
    <el-dialog v-model="createVisible" title="创建招标项目（FR-4.2-10-1）" width="760px" :close-on-click-modal="false">
      <el-alert v-if="createMsg" type="error" show-icon :closable="false" :title="createMsg" style="margin-bottom: 10px;" />
      <el-form label-width="110px">
        <el-row :gutter="12">
          <el-col :span="24">
            <el-form-item label="招标名称" required>
              <el-input v-model="form.title" placeholder="如：2026 年度不锈钢管件招标" maxlength="128" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="评标办法" required>
              <el-select v-model="form.evalMethod" style="width: 100%;">
                <el-option label="综合评分法" value="SCORE" />
                <el-option label="最低价法" value="LOWEST" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="招标品类">
              <el-input v-model="form.categoryCode" placeholder="品类编码" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="年度预估量">
              <el-input-number v-model="form.estAnnualQty" :min="0" :precision="2" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="报名截止" required>
              <el-date-picker v-model="form.regDeadline" type="datetime" value-format="YYYY-MM-DD HH:mm" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="报价截止" required>
              <el-date-picker v-model="form.quoteDeadline" type="datetime" value-format="YYYY-MM-DD HH:mm" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="技术规格">
              <el-input v-model="form.techSpec" type="textarea" :rows="2" placeholder="附件桩：文本说明（招标文件包、图纸编号等）" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="资格门槛">
              <el-input v-model="form.qualifyReq" type="textarea" :rows="2" placeholder="营业执照、行业认证、样品检测合格等" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="权重-价格">
              <el-input-number v-model="form.weightPrice" :min="0" :max="100" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="权重-交付">
              <el-input-number v-model="form.weightDelivery" :min="0" :max="100" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="权重-质量">
              <el-input-number v-model="form.weightQuality" :min="0" :max="100" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="权重-配合度">
              <el-input-number v-model="form.weightCooperation" :min="0" :max="100" style="width: 100%;" />
              <div style="font-size: 12px; color: #909399;">四项之和须为 100，当前 {{ weightSum }}</div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">招标行（物料）</el-divider>
        <el-table :data="form.lines" size="small" border>
          <el-table-column label="物料编码" width="200">
            <template #default="{ row }">
              <el-select v-model="row.itemCode" filterable allow-create default-first-option placeholder="选择或输入" style="width: 100%;">
                <el-option v-for="o in itemOptions" :key="o.code" :label="`[${o.code}] ${o.name}`" :value="o.code" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="名称" min-width="140">
            <template #default="{ row }"><el-input v-model="row.itemName" /></template>
          </el-table-column>
          <el-table-column label="数量" width="130">
            <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" :precision="4" style="width: 100%;" /></template>
          </el-table-column>
          <el-table-column label="单位" width="90">
            <template #default="{ row }"><el-input v-model="row.unit" /></template>
          </el-table-column>
          <el-table-column label="需求日期" width="160">
            <template #default="{ row }">
              <el-date-picker v-model="row.reqDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" @click="form.lines.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button style="margin-top: 8px;" @click="form.lines.push({ itemCode: '', itemName: '', qty: 1, unit: '', reqDate: '' })">+ 添加行</el-button>

        <el-divider content-position="left">投标方</el-divider>
        <el-select v-model="form.supplierIds" multiple filterable collapse-tags collapse-tags-tooltip
                   placeholder="选择投标供应商" style="width: 100%;">
          <el-option v-for="s in supplierOptions" :key="s.id" :label="s.supplierName" :value="s.id" />
        </el-select>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ================= 详情抽屉 ================= -->
    <el-drawer v-model="detailVisible" :title="detail.tender ? `${detail.tender.tenderNo} ${detail.tender.title}` : '详情'" size="760px">
      <template v-if="detail.tender">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px;">
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.tender.status)" size="small">{{ statusName(detail.tender.status) }}</el-tag>
            <el-tag v-if="detail.tender.invitePending === '1'" type="warning" size="small" style="margin-left:4px;">转邀请待批</el-tag>
            <el-tag v-if="detail.tender.anomalyFlag === '1'" type="danger" size="small" style="margin-left:4px;">评标冻结</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="评标办法">
            {{ detail.tender.evalMethod === 'LOWEST' ? '最低价法' : '综合评分法' }}
          </el-descriptions-item>
          <el-descriptions-item label="报名截止">{{ detail.tender.regDeadline || '—' }}</el-descriptions-item>
          <el-descriptions-item label="报价截止">{{ detail.tender.quoteDeadline || '—' }}</el-descriptions-item>
          <el-descriptions-item label="权重快照">
            价{{ detail.tender.weightPrice }}/交{{ detail.tender.weightDelivery }}/质{{ detail.tender.weightQuality }}/合{{ detail.tender.weightCooperation }}
          </el-descriptions-item>
          <el-descriptions-item label="合格投标方">{{ detail.qualifiedCount }} 家</el-descriptions-item>
          <el-descriptions-item label="公示期">{{ detail.tender.publicityStart || '—' }} ~ {{ detail.tender.publicityEnd || '—' }}</el-descriptions-item>
          <el-descriptions-item label="中标">
            <span v-if="detail.tender.awardSupplierId">
              {{ supplierName(detail.tender.awardSupplierId) }} · {{ detail.tender.awardPrice }}
              <span v-if="detail.tender.awardScore"> · {{ detail.tender.awardScore }} 分</span>
            </span>
            <span v-else style="color:#c0c4cc;">—</span>
          </el-descriptions-item>
        </el-descriptions>

        <div style="margin-bottom: 8px; display: flex; gap: 8px; flex-wrap: wrap;">
          <el-button v-if="detail.tender.status === 'DRAFT'" size="small" type="primary" @click="doStart">开始报名</el-button>
          <template v-if="detail.tender.status === 'BIDDING'">
            <el-button size="small" @click="doPostponeReg">延长报名期</el-button>
            <el-button v-if="detail.tender.tenderType !== 'INVITE' || detail.tender.invitePending === '3'" size="small" @click="doToInvite">转邀请招标</el-button>
            <el-button v-if="detail.tender.invitePending === '1'" size="small" type="warning" @click="doInviteApproval(true)">批准转邀请</el-button>
            <el-button v-if="detail.tender.invitePending === '1'" size="small" @click="doInviteApproval(false)">驳回转邀请</el-button>
            <el-button size="small" type="warning" @click="doPostponeQuote">延长报价截止</el-button>
          </template>
          <el-button v-if="detail.tender.status === 'LOCKED'" size="small" type="primary" @click="doOpen">开标</el-button>
          <template v-if="detail.tender.status === 'EVALUATING'">
            <el-button size="small" @click="doAnomaly(true)">标记异常冻结</el-button>
            <el-button v-if="detail.tender.anomalyFlag === '1'" size="small" type="success" @click="doAnomaly(false)">解除异常</el-button>
            <el-button size="small" type="primary" @click="doEvaluate">评标汇总定标</el-button>
          </template>
          <el-button v-if="detail.tender.status === 'AWAITING_PUBLICITY'" size="small" type="danger" @click="doObjection">登记有效异议</el-button>
          <template v-if="detail.tender.status === 'OBJECTION'">
            <el-button size="small" type="success" @click="doReview('MAINTAIN')">复核维持</el-button>
            <el-button size="small" type="danger" @click="doReview('REBID')">复核重新招标</el-button>
          </template>
          <el-button v-if="['DRAFT','BIDDING','LOCKED','EVALUATING','PENDING_AWARD','AWAITING_PUBLICITY','OBJECTION'].includes(detail.tender.status)"
                     size="small" type="danger" plain @click="doCancel">作废</el-button>
        </div>

        <el-divider content-position="left">评标委员（ROLE_BID_JUDGE，评标开始前须指定）</el-divider>
        <div style="display: flex; gap: 8px;">
          <el-input v-model="judgeInput" placeholder="评委用户ID，逗号分隔，如 user-zhangsan,user-admin"
                    style="flex: 1;" :disabled="judgeLocked" />
          <el-button size="small" type="primary" :disabled="judgeLocked" @click="doSetJudges">保存评委</el-button>
        </div>
        <div v-if="(detail.judges || []).length" style="margin-top: 6px;">
          <el-tag v-for="j in detail.judges" :key="j.judgeUserId" size="small" style="margin-right: 6px;">
            {{ j.judgeName || j.judgeUserId }}
          </el-tag>
        </div>
        <div v-else style="margin-top: 6px; font-size: 12px; color: #909399;">
          尚未指定评委。综合评分法须在开标前指定，否则开标会被阻断。
        </div>

        <el-divider content-position="left">投标方与资格审查（BR-4.2-45）</el-divider>
        <el-table :data="detail.suppliers || []" size="small" border>
          <el-table-column prop="supplierName" label="供应商" min-width="140" />
          <el-table-column label="来源" width="70" align="center">
            <template #default="{ row }">{{ row.joinSource === 'INVITE' ? '邀请' : '报名' }}</template>
          </el-table-column>
          <el-table-column label="资格" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="{ PASS: 'success', FAIL: 'danger', PENDING: 'info' }[row.qualifyStatus]" size="small">
                {{ { PASS: '合格', FAIL: '不合格', PENDING: '待审' }[row.qualifyStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="qualifyNote" label="审查意见" min-width="110" show-overflow-tooltip />
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="success" :disabled="!canQualify" @click="doQualify(row, 'PASS')">通过</el-button>
              <el-button link type="danger" :disabled="!canQualify" @click="doQualify(row, 'FAIL')">不通过</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button v-if="canQualify" size="small" style="margin-top: 8px;" @click="doAddSupplier">追加投标方</el-button>

        <el-divider content-position="left">多轮报价（只降不升，历史只读）</el-divider>
        <el-table :data="quoteRows" size="small" border>
          <el-table-column prop="supplierName" label="投标方" min-width="130" />
          <el-table-column prop="roundNo" label="轮次" width="70" align="center" />
          <el-table-column prop="unitPrice" label="单价" width="110" align="right" />
          <el-table-column prop="quoteTime" label="录入时间" width="155" />
          <el-table-column prop="operator" label="代录人" width="110" />
        </el-table>
        <div v-if="detail.tender.status === 'BIDDING'" style="margin-top: 8px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap;">
          <span style="font-size: 12px; color: #909399;">投标方：</span>
          <el-radio-group v-model="quoteForm.supplierId" size="small">
            <el-radio-button v-for="s in qualifiedSuppliers" :key="s.supplierId" :value="s.supplierId">
              {{ s.supplierName }}
            </el-radio-button>
          </el-radio-group>
          <el-input-number v-model="quoteForm.unitPrice" :min="0" :precision="4" placeholder="单价" style="width: 160px;" />
          <el-button type="primary" size="small" @click="doSaveQuote">录入报价（新轮次）</el-button>
        </div>

        <template v-if="(detail.judges || []).length">
          <el-divider content-position="left">评分汇总（各评委加权分 → 算术平均）</el-divider>
          <el-table :data="summaryRows" size="small" border>
            <el-table-column prop="supplierName" label="投标方" min-width="140" />
            <el-table-column v-for="j in detail.judges" :key="j.judgeUserId" :label="j.judgeName || j.judgeUserId" width="120" align="center">
              <template #default="{ row }">{{ scoreOf(j, row.supplierId) }}</template>
            </el-table-column>
            <el-table-column label="平均" width="100" align="center">
              <template #default="{ row }"><b>{{ avgOf(row.supplierId) }}</b></template>
            </el-table-column>
          </el-table>
        </template>

        <template v-if="(detail.lines || []).length">
          <el-divider content-position="left">招标行</el-divider>
          <el-table :data="detail.lines" size="small" border>
            <el-table-column prop="itemCode" label="物料" width="150" />
            <el-table-column prop="itemName" label="名称" min-width="120" />
            <el-table-column prop="qty" label="数量" width="100" align="right" />
            <el-table-column prop="reqDate" label="需求日期" width="110" />
          </el-table>
        </template>
      </template>
    </el-drawer>

    <!-- ================= 评分对话框 ================= -->
    <el-dialog v-model="scoreVisible" title="提交评分（FR-4.2-10-2）" width="460px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="投标方">
          <el-input :model-value="scoreForm.supplierName" disabled />
        </el-form-item>
        <el-form-item label="价格">
          <el-input-number v-model="scoreForm.scorePrice" :min="0" :max="100" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="交付">
          <el-input-number v-model="scoreForm.scoreDelivery" :min="0" :max="100" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="质量">
          <el-input-number v-model="scoreForm.scoreQuality" :min="0" :max="100" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="配合度">
          <el-input-number v-model="scoreForm.scoreCooperation" :min="0" :max="100" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="加权得分">
          <b style="font-size: 16px;">{{ previewScore }}</b>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scoreVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitScore">提交并锁定</el-button>
      </template>
    </el-dialog>

    <!-- ============ 多中标人定标录入（change add-framework-agreement-order，design D2） ============ -->
    <el-dialog v-model="winnersVisible" title="定标录入：中标人与份额（Σ份额必须 = 100%）" width="720px"
      :close-on-click-modal="false">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px;"
        title="默认预填评标建议值（评分法=加权最高者 / 最低价法=最低报价者，单家 100%），可增删调整"
        description="每家中标人须为合格投标方，单价自动取其最终轮有效报价（不可改）；保存后头快照 = 份额最大中标人。" />
      <el-table :data="winnersRows" size="small" border>
        <el-table-column label="#" width="50" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="中标人" min-width="200">
          <template #default="{ row }">
            <el-select v-model="row.supplierId" placeholder="选择投标方" filterable style="width: 100%;"
              @change="onWinnerPick(row)">
              <el-option v-for="c in winnerCandidates" :key="c.supplierId"
                :label="`${c.supplierName}（${c.score != null ? c.score + '分' : '价' + c.finalPrice}）`"
                :value="c.supplierId" :disabled="isWinnerPicked(c.supplierId, row)" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="中标单价(锁定)" width="130" align="right">
          <template #default="{ row }">
            <el-input-number v-model="row.awardPrice" :min="0" :precision="4" :controls="false"
              disabled style="width: 120px;" />
          </template>
        </el-table-column>
        <el-table-column label="份额 %" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.sharePct" :min="0" :max="100" :precision="2" size="small"
              style="width: 120px;" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" :disabled="winnersRows.length <= 1"
              @click="winnersRows.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 10px; display: flex; gap: 12px; align-items: center;">
        <el-button size="small" :disabled="winnersRows.length >= winnerCandidates.length"
          @click="addWinnerRow">+ 加中标人</el-button>
        <span :style="{ color: winnersSum === 100 ? '#67C23A' : '#F56C6C', fontWeight: 600 }">
          份额合计：{{ winnersSum.toFixed(2) }}% {{ winnersSum === 100 ? '✓' : '（须 = 100）' }}
        </span>
      </div>
      <template #footer>
        <el-button @click="winnersVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="winnersSum !== 100"
          @click="saveWinners">保存并提交定标</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import {
  getTenderPageApi, getTenderDetailApi, getMyTasksApi, getApprovalTodoApi,
  createTenderApi, startTenderApi, cancelTenderApi, qualifyTenderApi,
  addTenderSuppliersApi, postponeRegApi, toInviteApi, inviteApprovalApi,
  saveTenderQuoteApi, postponeTenderApi, openTenderApi, setJudgesApi,
  saveScoreApi, evaluateTenderApi, setAnomalyApi, approveAwardApi,
  raiseObjectionApi, reviewObjectionApi, saveWinnersApi
} from '@/api/proc/tender'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { getItemOptionsApi } from '@/api/mdm/item'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const activeTab = ref('list')
const loading = ref(false)
const saving = ref(false)

const statusNames = {
  DRAFT: '立项草稿', BIDDING: '报名竞价中', LOCKED: '已锁价', EVALUATING: '评标中',
  PENDING_AWARD: '待定标审批', AWAITING_PUBLICITY: '公示中', OBJECTION: '异议复核',
  AWARDED: '已定标', CANCELLED: '已作废'
}
function statusName(s) { return statusNames[s] || s }
function statusTag(s) {
  return {
    DRAFT: 'info', BIDDING: 'primary', LOCKED: 'warning', EVALUATING: 'warning',
    PENDING_AWARD: 'danger', AWAITING_PUBLICITY: 'primary', OBJECTION: 'danger',
    AWARDED: 'success', CANCELLED: 'info'
  }[s]
}

// ================= 列表 =================
const listQuery = ref({ status: '', keyword: '', current: 1, size: 10 })
const rows = ref([])
const total = ref(0)
const supplierOptions = ref([])
const itemOptions = ref([])

async function loadList(page) {
  if (page) listQuery.value.current = page
  loading.value = true
  try {
    const res = await getTenderPageApi(listQuery.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ================= 创建 =================
const createVisible = ref(false)
const createMsg = ref('')
const form = reactive({
  title: '', evalMethod: 'SCORE', categoryCode: '', categoryName: '',
  estAnnualQty: null, techSpec: '', qualifyReq: '',
  regDeadline: '', quoteDeadline: '',
  weightPrice: 40, weightDelivery: 25, weightQuality: 25, weightCooperation: 10,
  lines: [{ itemCode: '', itemName: '', qty: 1, unit: '', reqDate: '' }],
  supplierIds: []
})
const weightSum = computed(() =>
  form.weightPrice + form.weightDelivery + form.weightQuality + form.weightCooperation)

function openCreate() {
  createMsg.value = ''
  createVisible.value = true
}

async function submitCreate() {
  createMsg.value = ''
  if (weightSum.value !== 100) {
    createMsg.value = `四项评分权重之和须为 100，当前 ${weightSum.value}`
    return
  }
  saving.value = true
  try {
    const res = await createTenderApi({ ...form, categoryForm: undefined })
    createVisible.value = false
    ElMessage.success(`创建成功：${res.data.tender.tenderNo}`)
    loadList(1)
  } catch (e) {
    createMsg.value = e?.message || '创建失败'
  } finally {
    saving.value = false
  }
}

// ================= 详情 =================
const detailVisible = ref(false)
const detail = reactive({ tender: null, suppliers: [], lines: [], judges: [], quotes: {} })
const quoteForm = reactive({ supplierId: '', unitPrice: null })

const canQualify = computed(() =>
  detail.tender && ['DRAFT', 'BIDDING'].includes(detail.tender.status))

// 评标开始后不可更换评委
const judgeLocked = computed(() =>
  !detail.tender
  || ['EVALUATING', 'PENDING_AWARD', 'AWAITING_PUBLICITY', 'OBJECTION', 'AWARDED', 'CANCELLED']
      .includes(detail.tender.status))
const judgeInput = ref('')

const quoteRows = computed(() => {
  const out = []
  const names = {}
  for (const s of detail.suppliers || []) names[s.supplierId] = s.supplierName
  for (const [sid, list] of Object.entries(detail.quotes || {})) {
    for (const q of list) out.push({ ...q, supplierName: names[sid] || sid })
  }
  return out.sort((a, b) => (a.roundNo - b.roundNo))
})

const summaryRows = computed(() => (detail.suppliers || [])
  .filter(s => s.qualifyStatus === 'PASS')
  .map(s => ({ supplierId: s.supplierId, supplierName: s.supplierName })))

/** 合格投标方（报价录入候选，通常 3-6 家，用按钮组而非搜索下拉） */
const qualifiedSuppliers = computed(() => (detail.suppliers || [])
  .filter(s => s.qualifyStatus === 'PASS'))

/** 评标中的招标（评标 Tab 候选，通常数量有限，用按钮组展示） */
const evaluatingTenders = computed(() => rows.value.filter(r => r.status === 'EVALUATING'))

function scoreOf(judge, supplierId) {
  const s = (judge.scores || []).find(x => x.supplierId === supplierId)
  return s ? (s.weightedScore ?? '—') : '—'
}
function avgOf(supplierId) {
  const vals = (detail.judges || [])
    .map(j => scoreOf(j, supplierId))
    .filter(v => typeof v === 'number')
  if (!vals.length) return '—'
  return (vals.reduce((a, b) => a + b, 0) / vals.length).toFixed(2)
}
function supplierName(id) {
  const s = (detail.suppliers || []).find(x => x.supplierId === id)
  if (s) return s.supplierName
  const o = supplierOptions.value.find(x => x.id === id)
  return o ? o.supplierName : id
}

async function loadDetail(id) {
  const res = await getTenderDetailApi(id)
  Object.assign(detail, res.data)
}
async function openDetail(row) { await openDetailById(row.id) }
async function openDetailById(id) {
  await loadDetail(id)
  quoteForm.supplierId = ''
  quoteForm.unitPrice = null
  judgeInput.value = (detail.judges || []).map(j => j.judgeUserId).join(',')
  detailVisible.value = true
}

async function doSetJudges() {
  const ids = judgeInput.value.split(',').map(s => s.trim()).filter(Boolean)
  if (!ids.length) { ElMessage.warning('请填写至少 1 位评委用户 ID'); return }
  await confirmAction(`将评委名单替换为 ${ids.length} 人？`, () => setJudgesApi(detail.tender.id, ids))
  ElMessage.success('评委已保存')
}

async function reload() {
  if (detail.tender) await loadDetail(detail.tender.id)
  await loadList()
}

// ---- 状态推进 ----
async function confirmAction(msg, fn) {
  await ElMessageBox.confirm(msg, '确认操作', { type: 'warning' })
  await fn()
  await reload()
}

async function doStart() {
  await confirmAction('开始报名与竞价？', () => startTenderApi(detail.tender.id))
}
async function doCancel() {
  const { value } = await ElMessageBox.prompt('作废原因（不少于 2 字）', '作废招标', {
    inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字'
  })
  await cancelTenderApi(detail.tender.id, value)
  ElMessage.success('已作废')
  await reload()
}
async function doQualify(row, status) {
  const { value } = await ElMessageBox.prompt(
    status === 'PASS' ? '审查意见（可选）' : '不合格原因', '资格审查',
    { inputPattern: status === 'PASS' ? /^[\s\S]*$/ : /^.{2,}$/,
      inputErrorMessage: status === 'PASS' ? '填写任意内容' : '原因不少于 2 字' })
  const res = await qualifyTenderApi(detail.tender.id, row.supplierId, status, value)
  ElMessage.success(`合格投标方 ${res.data.qualifiedCount} 家`
    + (res.data.insufficient ? `（不足 ${res.data.minQuoteCount} 家，开标将被阻断）` : ''))
  await reload()
}
async function doAddSupplier() {
  const { value } = await ElMessageBox.prompt('输入供应商 ID（逗号分隔）', '追加投标方', {
    inputPattern: /^.{3,}$/, inputErrorMessage: '请输入'
  })
  await addTenderSuppliersApi(detail.tender.id, value.split(',').map(s => s.trim()))
  ElMessage.success('已追加')
  await reload()
}
async function doPostponeReg() {
  const { value } = await ElMessageBox.prompt('新的报名截止（yyyy-MM-dd HH:mm），须更晚', '延长报名期', {
    inputPattern: /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/, inputErrorMessage: '格式 yyyy-MM-dd HH:mm'
  })
  const { value: reason } = await ElMessageBox.prompt('延期原因', '延长报名期', {
    inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字'
  })
  const res = await postponeRegApi(detail.tender.id, value, reason)
  ElMessage.success(`报名期延至 ${res.data.regDeadline}`)
  await reload()
}
async function doPostponeQuote() {
  const { value } = await ElMessageBox.prompt('新的报价截止（yyyy-MM-dd HH:mm），须更晚', '延长报价截止', {
    inputPattern: /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/, inputErrorMessage: '格式 yyyy-MM-dd HH:mm'
  })
  const { value: reason } = await ElMessageBox.prompt('延期原因', '延长报价截止', {
    inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字'
  })
  await postponeTenderApi(detail.tender.id, value, reason)
  ElMessage.success('报价截止已延期')
  await reload()
}
async function doToInvite() {
  const { value } = await ElMessageBox.prompt('变更原因（将升级采购总监审批）', '转邀请招标', {
    inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字'
  })
  await toInviteApi(detail.tender.id, value)
  ElMessage.success('已提交，待采购总监审批')
  await reload()
}
async function doInviteApproval(approved) {
  await confirmAction(approved ? '批准转为邀请招标？' : '驳回，回退为公开招标？',
    async () => {
      const { value } = await ElMessageBox.prompt('审批意见', '转邀请审批', {
        inputPattern: /^.{2,}$/, inputErrorMessage: '意见不少于 2 字'
      })
      await inviteApprovalApi(detail.tender.id, approved, value)
      ElMessage.success(approved ? '已批准' : '已驳回，回退公开招标')
    })
}
async function doOpen() {
  await confirmAction('开标并进入评标？（BR-4.2-45：合格投标方不足将被阻断）',
    () => openTenderApi(detail.tender.id))
}
async function doSaveQuote() {
  if (!quoteForm.supplierId) { ElMessage.warning('请选择投标方'); return }
  if (quoteForm.unitPrice == null) { ElMessage.warning('请输入单价'); return }
  try {
    const res = await saveTenderQuoteApi(detail.tender.id, {
      supplierId: quoteForm.supplierId, unitPrice: quoteForm.unitPrice
    })
    ElMessage.success(`已录入第 ${res.data.roundNo} 轮，单价 ${res.data.unitPrice}`)
    quoteForm.unitPrice = null
    await reload()
  } catch (e) {
    // 弹错由 request.js 单点处理，此处仅静默
  }
}
async function doAnomaly(anomaly) {
  const { value } = await ElMessageBox.prompt(
    anomaly ? '异常说明（标记后冻结评标）' : '解除说明', '异常处置',
    { inputPattern: /^.{2,}$/, inputErrorMessage: '说明不少于 2 字' })
  await setAnomalyApi(detail.tender.id, anomaly, value)
  ElMessage.success(anomaly ? '已标记异常并冻结评标' : '已解除，恢复评标')
  await reload()
}
async function doEvaluate() {
  await ElMessageBox.confirm('评标汇总并进入定标录入？（多中标人与份额在此录入）', '评标汇总',
    { type: 'warning' })
  const { data } = await evaluateTenderApi(detail.tender.id)
  // 打开多中标录入弹窗，默认预填评标建议值（spec「评标结果作为定标默认建议」）
  winnersTenderId.value = detail.tender.id
  winnersCandidates.value = data.candidates || []
  winnersRows.value = (data.awardWinners || []).map(w => ({
    supplierId: w.supplierId, awardPrice: Number(w.awardPrice), sharePct: Number(w.sharePct)
  }))
  winnersVisible.value = true
  await reload()
}

// ---- 多中标人定标录入（spec tender-bidding-management） ----
const winnersVisible = ref(false)
const winnersTenderId = ref('')
const winnersCandidates = ref([])
const winnersRows = ref([])
const winnersSum = computed(() =>
  winnersRows.value.reduce((s, r) => s + (Number(r.sharePct) || 0), 0))

const winnerCandidates = computed(() => winnersCandidates.value)

function isWinnerPicked(supplierId, currentRow) {
  return winnersRows.value.some(r => r !== currentRow && r.supplierId === supplierId)
}

function onWinnerPick(row) {
  const c = winnersCandidates.value.find(x => x.supplierId === row.supplierId)
  if (c) row.awardPrice = Number(c.finalPrice)   // 单价 = 最终轮有效报价（锁定）
}

function addWinnerRow() {
  const used = new Set(winnersRows.value.map(r => r.supplierId))
  const next = winnersCandidates.value.find(c => !used.has(c.supplierId))
  if (!next) { ElMessage.info('无更多合格投标方可选'); return }
  winnersRows.value.push({
    supplierId: next.supplierId, awardPrice: Number(next.finalPrice), sharePct: 0
  })
}

async function saveWinners() {
  if (winnersSum.value !== 100) { ElMessage.warning('份额合计须等于 100%'); return }
  const winners = winnersRows.value.map(r => ({
    supplierId: r.supplierId, awardPrice: r.awardPrice, sharePct: r.sharePct
  }))
  saving.value = true
  try {
    await saveWinnersApi(winnersTenderId.value, winners)
    ElMessage.success(`已保存 ${winners.length} 家中标人，份额合计 100%`)
    winnersVisible.value = false
    await reload()
  } finally {
    saving.value = false
  }
}
async function doObjection() {
  const { value } = await ElMessageBox.prompt('异议内容（登记后暂停协议生成）', '登记异议', {
    inputPattern: /^.{2,}$/, inputErrorMessage: '内容不少于 2 字'
  })
  await raiseObjectionApi(detail.tender.id, value, true)
  ElMessage.success('异议已登记，定标已冻结')
  await reload()
}
async function doReview(verdict) {
  const { value } = await ElMessageBox.prompt(
    verdict === 'MAINTAIN' ? '复核结论（维持原定标）' : '复核结论（重新招标）', '异议复核',
    { inputPattern: /^.{2,}$/, inputErrorMessage: '结论不少于 2 字' })
  const pending = (detail.objections || []).find(o => o.reviewStatus === 'PENDING')
  if (!pending) { ElMessage.warning('未找到待复核的异议记录'); return }
  await reviewObjectionApi(detail.tender.id, pending.id, verdict, value)
  ElMessage.success(verdict === 'MAINTAIN' ? '维持原定标，恢复公示' : '已裁定重新招标，原定标作废')
  await reload()
}

// ---- 定标审批 ----
const todoRows = ref([])
const todoLoading = ref(false)
async function loadTodo() {
  todoLoading.value = true
  try {
    const res = await getApprovalTodoApi()
    todoRows.value = res.data
  } finally {
    todoLoading.value = false
  }
}
async function onAwardApprove(row, approved) {
  const { value } = await ElMessageBox.prompt('审批意见', approved ? '通过定标' : '驳回定标', {
    inputPattern: /^.{2,}$/, inputErrorMessage: '意见不少于 2 字'
  })
  const res = await approveAwardApi(row.tenderId, approved, value)
  ElMessage.success(approved
    ? `已通过，公示至 ${res.data.publicityEnd}（${res.data.publicityDays} 天）`
    : '已驳回，回到评标')
  await loadTodo()
  await loadList()
}

// ---- 我的评标 ----
const myTenderId = ref('')
const myTasks = ref([])
const myLoading = ref(false)
const myMsg = ref('')
async function loadMyTasks() {
  if (!myTenderId.value) return
  myMsg.value = ''
  myLoading.value = true
  try {
    const res = await getMyTasksApi(myTenderId.value)
    myTasks.value = res.data.tasks || []
  } catch (e) {
    myMsg.value = e?.message || '加载失败'
    myTasks.value = []
  } finally {
    myLoading.value = false
  }
}

const scoreVisible = ref(false)
const scoreForm = reactive({ supplierId: '', supplierName: '', scorePrice: 80, scoreDelivery: 80, scoreQuality: 80, scoreCooperation: 80 })
const previewScore = computed(() => {
  const w = detail.tender || { weightPrice: 40, weightDelivery: 25, weightQuality: 25, weightCooperation: 10 }
  const v = (scoreForm.scorePrice * (w.weightPrice ?? 40)
    + scoreForm.scoreDelivery * (w.weightDelivery ?? 25)
    + scoreForm.scoreQuality * (w.weightQuality ?? 25)
    + scoreForm.scoreCooperation * (w.weightCooperation ?? 10)) / 100
  return v.toFixed(2)
})
function openScore(row) {
  Object.assign(scoreForm, {
    supplierId: row.supplierId, supplierName: row.supplierName,
    scorePrice: row.scorePrice ?? 80, scoreDelivery: row.scoreDelivery ?? 80,
    scoreQuality: row.scoreQuality ?? 80, scoreCooperation: row.scoreCooperation ?? 80
  })
  scoreVisible.value = true
}
async function submitScore() {
  saving.value = true
  try {
    await saveScoreApi(myTenderId.value, { ...scoreForm, status: 'SUBMITTED' })
    ElMessage.success(`已提交，加权得分 ${previewScore.value}`)
    scoreVisible.value = false
    await loadMyTasks()
  } finally {
    saving.value = false
  }
}

async function loadOptions() {
  try {
    const res = await getSupplierPageApi({ current: 1, size: 100 })
    supplierOptions.value = res.data.records || []
  } catch (e) { /* 静默：仅影响下拉候选 */ }
  try {
    const res = await getItemOptionsApi()
    itemOptions.value = Array.isArray(res.data) ? res.data : (res.data?.options || [])
  } catch (e) { /* 静默 */ }
}

async function init() {
  await Promise.all([loadList(1), loadOptions()])
  if (activeTab.value === 'approval') loadTodo()
}
onMounted(init)
onActivated(init)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
