<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      :title="tab === 'plan' ? '点检保养（7.3.1）：维护计划 → 批量生成计划工单（幂等/停机顺延）→ 执行 → 关闭成本归集'
        : tab === 'repair' ? '故障维修（7.3.2）：全员报修 → 工程师确认派工（BR-16 挂锁阻断）→ 双验收 → BR-19 大修评估'
        : '备件领用（7.3.3）：关联工单领料，超清单须说明（BR-17）；走库存 MAINT 领料单扣减'"
      :description="tab === 'plan'
        ? '关键设备计划须设备主管审批；人工成本 = 工时 × SysParam 岗位费率（缺参标待调整，不阻断关闭）。'
        : tab === 'repair'
        ? '安全停机挂锁解除限设备主管；同故障 12 月 ≥3 次触发大修/更换评估并冻结同类工单（BR-19）。'
        : '关闭前置（BR-20）：未过账领料/未回填工时/未闭子工单/外协未传验收单 → 阻断并列清单。'" />

    <!-- ================= 7.3.1 点检保养 ================= -->
    <template v-if="tab === 'plan'">
      <el-card shadow="never">
        <div class="toolbar">
          <el-button type="primary" @click="openPlanDialog()">新建计划</el-button>
          <el-button type="success" :loading="generating" @click="doGenerate">生成计划工单</el-button>
          <span class="spacer" />
          <el-button plain size="small" @click="loadPlans">刷新</el-button>
        </div>

        <div v-if="genResult" class="gen-result">
          <el-alert :type="genResult.blocked && genResult.blocked.length ? 'warning' : 'success'"
            :closable="false" style="margin:8px 0;"
            :title="`生成结果（${genResult.window}）：新建 ${genResult.created.length} 张 / 顺延 ${genResult.postponed.length} / 阻断 ${genResult.blocked.length}`"
            :description="describeGen(genResult)" />
        </div>

        <el-table :data="planRows" size="small" border v-loading="loading" max-height="360">
          <el-table-column prop="planNo" label="计划号" width="150" />
          <el-table-column prop="cardNo" label="卡片号" width="140" />
          <el-table-column prop="itemName" label="维护项目" min-width="140" />
          <el-table-column prop="cycle" label="周期" width="60" align="center">
            <template #default="{ row }">{{ cycleLabel(row.cycle) }}</template>
          </el-table-column>
          <el-table-column label="关键" width="60" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.criticalFlag === '1'" type="danger" size="small">关键</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="approvalStatus" label="审批" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="approvalTag(row.approvalStatus)" size="small">{{ approvalLabel(row.approvalStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="nextPlanDate" label="下次计划日" width="110" align="center" />
          <el-table-column prop="estHours" label="预计工时" width="80" align="right" />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openPlanDialog(row)">编辑</el-button>
              <el-button v-if="row.approvalStatus === 'PENDING_APPROVE'" link type="warning" size="small"
                @click="doSubmitApproval(row)">提交审批</el-button>
              <el-button link type="danger" size="small" @click="doDisablePlan(row)">停用</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" style="margin-top:12px;">
        <template #header>维护工单（PREVENTIVE）</template>
        <el-table :data="planWoRows" size="small" border max-height="320">
          <el-table-column prop="woNo" label="工单号" width="150" />
          <el-table-column prop="cardNo" label="卡片号" width="140" />
          <el-table-column prop="planDesc" label="维护内容" min-width="160" show-overflow-tooltip />
          <el-table-column prop="planDate" label="计划日" width="100" align="center" />
          <el-table-column prop="status" label="状态" width="100" align="center">
            <template #default="{ row }"><el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="成本" width="110" align="right">
            <template #default="{ row }">¥ {{ fmt(row.totalCost) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="280" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="doAssign(row)">派工</el-button>
              <el-button link type="primary" size="small" :disabled="row.status !== 'ASSIGNED'"
                @click="act(woStart(row.woNo), '已开始执行')">开始</el-button>
              <el-button link type="primary" size="small" :disabled="row.status !== 'EXECUTING'"
                @click="openExecDialog(row)">执行记录</el-button>
              <el-button link type="warning" size="small" :disabled="row.status !== 'EXECUTING'"
                @click="act(woSubmitAcceptance(row.woNo), '已提交验收')">提交验收</el-button>
              <el-button link type="success" size="small" :disabled="row.status !== 'PENDING_ACCEPT'"
                @click="doAccept(row, 'ENG')">确认验收</el-button>
              <el-button link type="danger" size="small" :disabled="row.status !== 'PENDING_ACCEPT'"
                @click="doClose(row)">关闭</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- ================= 7.3.2 故障维修 ================= -->
    <template v-if="tab === 'repair'">
      <el-card shadow="never">
        <div class="toolbar">
          <el-button type="primary" @click="reportVisible = true">我要报修（全员可报）</el-button>
          <el-button plain size="small" @click="loadReports">刷新报修单</el-button>
          <span class="spacer" />
          <el-input v-model="historyCardId" placeholder="卡片ID 查设备履历" style="width:220px;" clearable />
          <el-button plain @click="loadHistory">设备履历</el-button>
        </div>
        <el-table :data="reportRows" size="small" border max-height="260" style="margin-top:8px;">
          <el-table-column prop="reportNo" label="报修单号" width="150" />
          <el-table-column prop="cardNo" label="卡片号" width="140" />
          <el-table-column prop="faultSymptom" label="故障现象" min-width="140" />
          <el-table-column label="安全风险" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.safetyRisk === '1'" type="danger" size="small">已挂锁</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" align="center">
            <template #default="{ row }">{{ reportLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'PENDING'">
                <el-button link type="primary" size="small" @click="openConvertDialog(row)">确认派工</el-button>
                <el-button link type="danger" size="small" @click="doRejectReport(row)">驳回</el-button>
              </template>
              <span v-else-if="row.woNo" class="muted">→ {{ row.woNo }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" style="margin-top:12px;">
        <template #header>维修工单（REPAIR）</template>
        <el-table :data="repairWoRows" size="small" border max-height="300">
          <el-table-column prop="woNo" label="工单号" width="150" />
          <el-table-column prop="cardNo" label="卡片号" width="140" />
          <el-table-column prop="faultSymptom" label="故障现象" min-width="130" show-overflow-tooltip />
          <el-table-column prop="resolvePlan" label="方案" width="70" align="center">
            <template #default="{ row }">{{ row.resolvePlan === 'OUTSOURCE' ? '外协' : '自主' }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100" align="center">
            <template #default="{ row }"><el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="评估" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.overhaulAssessFlag === '1'" type="warning" size="small">
                {{ row.assessStatus === 'PENDING_APPROVE' ? '评估中' : '已归档' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成本" width="110" align="right">
            <template #default="{ row }">¥ {{ fmt(row.totalCost) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="330" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="doAssign(row)">派工</el-button>
              <el-button link type="primary" size="small" :disabled="row.status !== 'ASSIGNED'"
                @click="act(woStart(row.woNo), '已开始执行')">开始</el-button>
              <el-button link type="primary" size="small" :disabled="row.status !== 'EXECUTING'"
                @click="openExecDialog(row)">执行</el-button>
              <el-button link type="info" size="small" :disabled="row.status !== 'EXECUTING'"
                @click="openSubDialog(row)">子工单</el-button>
              <el-button link type="warning" size="small" :disabled="row.status !== 'PENDING_ACCEPT'"
                @click="doAccept(row, 'OP')">操作员验收</el-button>
              <el-button link type="warning" size="small" :disabled="row.status !== 'PENDING_ACCEPT'"
                @click="doAccept(row, 'ENG')">工程师验收</el-button>
              <el-button link type="danger" size="small" :disabled="row.status !== 'PENDING_ACCEPT'"
                @click="doClose(row)">关闭</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" style="margin-top:12px;">
        <template #header>安全停机挂锁（BR-16：未解除阻断派工/调拨/处置）</template>
        <div class="toolbar">
          <el-input v-model="lockForm.cardId" placeholder="卡片ID" style="width:220px;" />
          <el-input v-model="lockForm.reason" placeholder="挂锁原因" style="width:280px;" />
          <el-button type="danger" plain @click="doLock">挂锁</el-button>
        </div>
        <el-table :data="lockRows" size="small" border max-height="220" style="margin-top:8px;">
          <el-table-column prop="lockNo" label="锁编号" width="170" />
          <el-table-column prop="cardNo" label="卡片号" width="140" />
          <el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip />
          <el-table-column prop="lockBy" label="挂锁人" width="100" />
          <el-table-column prop="status" label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'danger' : 'info'" size="small">
                {{ row.status === 'ACTIVE' ? '挂锁中' : '已解除' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'ACTIVE'" link type="warning" size="small"
                @click="doRelease(row)">解锁(主管)</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- ================= 7.3.3 备件领用 ================= -->
    <template v-if="tab === 'spares'">
      <el-card shadow="never">
        <div class="toolbar">
          <el-input v-model="spareWoNo" placeholder="执行中工单号" style="width:200px;" @change="loadWoIssues" />
          <el-button plain @click="loadWoIssues">查询该工单领料</el-button>
          <span class="spacer" />
          <el-button type="primary" :disabled="!spareWoNo" @click="spareVisible = true">发起领料</el-button>
        </div>
        <el-table :data="issueRows" size="small" border max-height="320" style="margin-top:8px;">
          <el-table-column prop="ISSUE_NO" label="领料单号" width="170" />
          <el-table-column prop="ISSUE_TYPE" label="类型" width="80" align="center" />
          <el-table-column prop="STATUS" label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.STATUS === 'POSTED' ? 'success' : 'info'" size="small">{{ row.STATUS }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="POST_DATE" label="过账时间" width="160" />
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:8px;"
          title="领料单过账后移动平均价自动回写行成本；工单关闭时备件成本 = 已过账 MAINT 领料行合计" />
      </el-card>
    </template>

    <!-- ================= 计划对话框 ================= -->
    <el-dialog v-model="planVisible" :title="planForm.id ? '编辑计划' : '新建维护计划'" width="560px">
      <el-form label-width="96px">
        <el-form-item label="资产卡片ID" required>
          <el-input v-model="planForm.cardId" placeholder="卡片 ID（erp_eam_asset_card.ID）" />
        </el-form-item>
        <el-form-item label="维护项目" required>
          <el-input v-model="planForm.itemName" placeholder="如：月度点检 / 换油保养" />
        </el-form-item>
        <el-form-item label="周期" required>
          <el-select v-model="planForm.cycle" style="width:140px;">
            <el-option label="每日" value="D" /><el-option label="每周" value="W" />
            <el-option label="每月" value="M" /><el-option label="每季" value="Q" />
            <el-option label="每年" value="Y" />
          </el-select>
        </el-form-item>
        <el-form-item label="维护标准">
          <el-input v-model="planForm.standard" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="备件清单">
          <el-input v-model="planForm.sparesJson" type="textarea" :rows="2"
            placeholder='JSON：[{"itemCode":"SP001","itemName":"滤芯","qty":2}]' />
        </el-form-item>
        <el-form-item label="预计工时">
          <el-input-number v-model="planForm.estHours" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="关键设备">
          <el-switch v-model="planForm.criticalFlag" active-value="1" inactive-value="0" />
          <span class="muted" style="margin-left:8px;">关键设备计划须设备主管审批后方可生成工单</span>
        </el-form-item>
        <el-form-item label="下次计划日">
          <el-date-picker v-model="planForm.nextPlanDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSavePlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- ================= 报修对话框（全员）================= -->
    <el-dialog v-model="reportVisible" title="设备报修（全员可报）" width="560px">
      <el-form label-width="96px">
        <el-form-item label="资产卡片ID" required>
          <el-input v-model="reportForm.cardId" />
        </el-form-item>
        <el-form-item label="故障现象" required>
          <el-input v-model="reportForm.faultSymptom" placeholder="如：主轴异响 / 液压漏油" />
        </el-form-item>
        <el-form-item label="发现时间" required>
          <el-date-picker v-model="reportForm.faultTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="影响范围">
          <el-input v-model="reportForm.impactScope" placeholder="如：停线 / 降速 / 无影响" />
        </el-form-item>
        <el-form-item label="详细描述">
          <el-input v-model="reportForm.detail" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="安全风险">
          <el-switch v-model="reportForm.safetyRisk" active-value="1" inactive-value="0" />
          <span class="muted" style="margin-left:8px;">勾选后提交即自动安全停机挂锁（解锁须设备主管）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reportVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSubmitReport">提交报修</el-button>
      </template>
    </el-dialog>

    <!-- ================= 确认派工对话框 ================= -->
    <el-dialog v-model="convertVisible" title="故障确认与派工" width="520px">
      <el-form label-width="96px">
        <el-form-item label="报修单">
          <span>{{ convertForm.reportNo }}（{{ convertForm.faultSymptom }}）</span>
        </el-form-item>
        <el-form-item label="维修人" required>
          <el-input v-model="convertForm.assignee" placeholder="指派维修人员" />
        </el-form-item>
        <el-form-item label="维修方案" required>
          <el-radio-group v-model="convertForm.resolvePlan">
            <el-radio value="SELF">自主维修</el-radio>
            <el-radio value="OUTSOURCE">外协维修</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="convertForm.resolvePlan === 'OUTSOURCE'" label="外协单位">
          <el-input v-model="convertForm.outsourceVendor" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="convertVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doConvert">确认派工</el-button>
      </template>
    </el-dialog>

    <!-- ================= 执行记录对话框 ================= -->
    <el-dialog v-model="execVisible" :title="`执行记录（${execForm.woNo}）`" width="560px">
      <el-form label-width="110px">
        <el-form-item label="实际内容">
          <el-input v-model="execForm.execDesc" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="发现的问题">
          <el-input v-model="execForm.issueDesc" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="更换部件">
          <el-input v-model="execForm.partsChanged" />
        </el-form-item>
        <el-form-item label="回填工时">
          <div style="display:flex;gap:8px;">
            <el-input-number v-model="execForm.hours" :min="0" :precision="2" placeholder="工时" />
            <el-select v-model="execForm.postType" style="width:130px;">
              <el-option label="工程师" value="ENGR" /><el-option label="维修工" value="TECH" />
              <el-option label="其他" value="OTHER" />
            </el-select>
            <el-button @click="addTime">追加工时</el-button>
          </div>
        </el-form-item>
      </el-form>
      <div v-if="execTimes.length" class="muted" style="margin-bottom:8px;">
        已回填：{{ execTimes.map(t => `${t.postType} ${t.hours}h`).join('，') }}
      </div>
      <template #footer>
        <el-button @click="execVisible = false">关闭</el-button>
        <el-button type="primary" :loading="saving" @click="doSaveExecution">保存执行记录</el-button>
      </template>
    </el-dialog>

    <!-- ================= 子工单对话框 ================= -->
    <el-dialog v-model="subVisible" :title="`创建子工单（父单 ${subForm.parentWoNo}）`" width="480px">
      <el-form label-width="90px">
        <el-form-item label="问题描述" required>
          <el-input v-model="subForm.desc" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="subVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreateSub">创建</el-button>
      </template>
    </el-dialog>

    <!-- ================= 发起领料对话框 ================= -->
    <el-dialog v-model="spareVisible" :title="`备件领料（工单 ${spareWoNo}）`" width="560px">
      <el-table :data="spareLines" size="small" border>
        <el-table-column label="物料编码" min-width="140">
          <template #default="{ row }"><el-input v-model="row.itemCode" size="small" /></template>
        </el-table-column>
        <el-table-column label="数量" width="130">
          <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" :precision="2" size="small" /></template>
        </el-table-column>
        <el-table-column label="" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="spareLines.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin:8px 0;" plain size="small" @click="spareLines.push({ itemCode: '', qty: 1 })">+ 加一行</el-button>
      <el-form label-width="90px">
        <el-form-item label="超领原因">
          <el-input v-model="spareOverReason" placeholder="领用超出工单备件清单时必填（BR-17 阻断）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="spareVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doRequestSpares">生成领料单</el-button>
      </template>
    </el-dialog>

    <!-- ================= 设备履历弹窗（6.3）================= -->
    <el-dialog v-model="historyVisible" :title="`设备履历（${historyCardId}）`" width="820px">
      <div v-if="history.stats" class="stat-row">
        <el-tag type="info">近 {{ history.stats.months }} 月故障 {{ history.stats.faultCount }} 次</el-tag>
        <el-tag type="info">MTBF {{ history.stats.mtbfDays ?? '—' }} 天</el-tag>
        <el-tag type="info">MTTR {{ history.stats.mttrHours ?? '—' }} 小时</el-tag>
        <el-tag v-if="history.stats.costSpike" type="danger">维修费用环比异常偏高（L4 预警）</el-tag>
      </div>
      <el-table :data="history.workOrders || []" size="small" border max-height="300" style="margin-top:8px;">
        <el-table-column prop="woNo" label="工单号" width="150" />
        <el-table-column prop="woType" label="类型" width="90" align="center">
          <template #default="{ row }">{{ row.woType === 'REPAIR' ? '故障维修' : '点检保养' }}</template>
        </el-table-column>
        <el-table-column prop="faultSymptom" label="故障/项目" min-width="130" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">{{ statusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column label="备件" width="90" align="right"><template #default="{ row }">¥ {{ fmt(row.spareCost) }}</template></el-table-column>
        <el-table-column label="人工" width="90" align="right"><template #default="{ row }">¥ {{ fmt(row.laborCost) }}</template></el-table-column>
        <el-table-column label="外协" width="90" align="right"><template #default="{ row }">¥ {{ fmt(row.outsourceAmount) }}</template></el-table-column>
        <el-table-column label="合计" width="100" align="right">
          <template #default="{ row }"><b>¥ {{ fmt(row.totalCost) }}</b></template>
        </el-table-column>
      </el-table>
      <el-table :data="history.safetyLocks || []" size="small" border max-height="160" style="margin-top:12px;">
        <el-table-column prop="lockNo" label="锁编号" width="170" />
        <el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip />
        <el-table-column prop="lockBy" label="挂锁人" width="100" />
        <el-table-column prop="status" label="状态" width="90" align="center" />
      </el-table>
      <el-table v-if="(history.notices || []).length" :data="history.notices" size="small" border
        max-height="160" style="margin-top:12px;">
        <el-table-column prop="TITLE" label="提示" min-width="160" show-overflow-tooltip />
        <el-table-column prop="BIZ_TYPE" label="类型" width="170" />
        <el-table-column prop="CREATE_DATE" label="时间" width="160" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  planPage, savePlan, submitPlanApproval, disablePlan, generatePlanOrders,
  woPage, woAssign, woStart, woExecution, woSubWorkOrder, woSubmitAcceptance,
  woAccept, woClose, woSpares,
  reportPage, submitReport, convertReport, rejectReport,
  lockPage, lockAsset, releaseLock,
  maintenanceHistory, maintenanceStats
} from '@/api/eam/maintenance'
import request from '@/utils/request'

const route = useRoute()
const tab = route.meta.tab || 'plan'

const loading = ref(false)
const saving = ref(false)
const generating = ref(false)

// ---- 7.3.1 ----
const planRows = ref([])
const planWoRows = ref([])
const planVisible = ref(false)
const planForm = reactive({ id: '', cardId: '', itemName: '', cycle: 'M', standard: '',
  sparesJson: '', estHours: null, criticalFlag: '0', nextPlanDate: '' })
const genResult = ref(null)

// ---- 7.3.2 ----
const reportRows = ref([])
const repairWoRows = ref([])
const reportVisible = ref(false)
const reportForm = reactive({ cardId: '', faultSymptom: '', faultTime: '', impactScope: '',
  detail: '', safetyRisk: '0' })
const convertVisible = ref(false)
const convertForm = reactive({ id: '', reportNo: '', faultSymptom: '', assignee: '',
  resolvePlan: 'SELF', outsourceVendor: '' })
const execVisible = ref(false)
const execForm = reactive({ woNo: '', execDesc: '', issueDesc: '', partsChanged: '', hours: null, postType: 'TECH' })
const execTimes = ref([])
const subVisible = ref(false)
const subForm = reactive({ parentWoNo: '', desc: '' })
const lockRows = ref([])
const lockForm = reactive({ cardId: '', reason: '' })
const historyCardId = ref(route.query.cardId || '')
const historyVisible = ref(false)
const history = reactive({ workOrders: [], safetyLocks: [], notices: [], stats: null })

// ---- 7.3.3 ----
const spareWoNo = ref('')
const issueRows = ref([])
const spareVisible = ref(false)
const spareLines = ref([{ itemCode: '', qty: 1 }])
const spareOverReason = ref('')

const fmt = (v) => (v == null ? '0.00' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2 }))
const cycleLabel = (c) => ({ D: '日', W: '周', M: '月', Q: '季', Y: '年' }[c] || c)
const statusLabel = (s) => ({ PENDING: '待执行', ASSIGNED: '已派工', EXECUTING: '执行中',
  PENDING_ACCEPT: '待验收', CLOSED: '已关闭', CANCELLED: '已取消' }[s] || s)
const statusTag = (s) => ({ PENDING: 'info', ASSIGNED: 'warning', EXECUTING: 'primary',
  PENDING_ACCEPT: 'warning', CLOSED: 'success', CANCELLED: 'info' }[s] || 'info')
const approvalLabel = (s) => ({ NOT_REQUIRED: '免审批', PENDING_APPROVE: '待审批',
  APPROVED: '已通过', REJECTED: '已驳回' }[s] || s)
const approvalTag = (s) => ({ NOT_REQUIRED: 'info', PENDING_APPROVE: 'warning',
  APPROVED: 'success', REJECTED: 'danger' }[s] || 'info')
const reportLabel = (s) => ({ PENDING: '待确认', CONVERTED: '已转工单', REJECTED: '已驳回' }[s] || s)

async function act(p, msg) {
  try {
    await p
    ElMessage.success(msg)
    await loadAll()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '操作失败')
  }
}

// ================= 加载 =================
async function loadPlans() {
  loading.value = true
  try {
    planRows.value = (await planPage({ current: 1, size: 100 })).data.records
    planWoRows.value = (await woPage({ current: 1, size: 100, woType: 'PREVENTIVE' })).data.records
  } finally { loading.value = false }
}
async function loadReports() {
  reportRows.value = (await reportPage({ current: 1, size: 100 })).data.records
  repairWoRows.value = (await woPage({ current: 1, size: 100, woType: 'REPAIR' })).data.records
  lockRows.value = (await lockPage({ current: 1, size: 100 })).data.records
}
async function loadWoIssues() {
  if (!spareWoNo.value) return
  const res = await request.get('/eam/work-orders/' + spareWoNo.value)
  issueRows.value = res.data.issues || []
}
async function loadAll() {
  try {
    if (tab === 'plan') await loadPlans()
    else if (tab === 'repair') { await loadReports(); if (historyCardId.value) await loadHistory() }
    else await loadWoIssues()
  } catch (e) {
    console.error('运维页数据加载失败', e)
    ElMessage.error(e?.response?.data?.msg || e.message || '加载失败')
  }
}
onMounted(loadAll)

// ================= 计划 =================
function openPlanDialog(row) {
  Object.assign(planForm, row || { id: '', cardId: '', itemName: '', cycle: 'M', standard: '',
    sparesJson: '', estHours: null, criticalFlag: '0', nextPlanDate: '' })
  planVisible.value = true
}
async function doSavePlan() {
  if (!planForm.cardId || !planForm.itemName) { ElMessage.warning('卡片与维护项目必填'); return }
  saving.value = true
  try {
    await savePlan({ ...planForm })
    ElMessage.success('计划已保存')
    planVisible.value = false
    await loadPlans()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '保存失败') } finally { saving.value = false }
}
async function doSubmitApproval(row) {
  await act(submitPlanApproval(row.id), '已提交设备主管审批')
}
async function doDisablePlan(row) {
  await ElMessageBox.confirm(`停用计划 ${row.planNo}？`, '提示', { type: 'warning' })
  await act(disablePlan(row.id), '已停用')
}
function describeGen(r) {
  const parts = []
  if (r.created.length) parts.push(`新建：${r.created.join('、')}`)
  if (r.postponed.length) parts.push(`顺延（停机中）：${r.postponed.join('、')}`)
  if (r.blocked.length) parts.push(`阻断：${r.blocked.join('、')}`)
  if (r.spareWarnings.length) parts.push(`备件不足预警：${r.spareWarnings.map(w => `${w.itemCode}(需${w.required}/可用${w.available})`).join('、')}`)
  return parts.join('；') || '窗口内无到期计划'
}
async function doGenerate() {
  generating.value = true
  try {
    const res = await generatePlanOrders()
    genResult.value = res.data
    ElMessage.success(`生成完成：新建 ${res.data.created.length} 张`)
    await loadPlans()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '生成失败') } finally { generating.value = false }
}

// ================= 工单动作 =================
async function doAssign(row) {
  const { value } = await ElMessageBox.prompt('指派维修人员', `派工 ${row.woNo}`, { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await act(woAssign(row.woNo, value), '派工成功（已校验安全停机）')
}
function openExecDialog(row) {
  Object.assign(execForm, { woNo: row.woNo, execDesc: row.execDesc || '', issueDesc: row.issueDesc || '',
    partsChanged: row.partsChanged || '', hours: null, postType: 'TECH' })
  execTimes.value = []
  execVisible.value = true
}
async function addTime() {
  if (!execForm.hours) { ElMessage.warning('填写工时数'); return }
  execTimes.value.push({ hours: execForm.hours, postType: execForm.postType })
  execForm.hours = null
}
async function doSaveExecution() {
  saving.value = true
  try {
    await woExecution(execForm.woNo, { execDesc: execForm.execDesc, issueDesc: execForm.issueDesc, partsChanged: execForm.partsChanged })
    for (const t of execTimes.value) {
      await request.post('/eam/work-orders/' + execForm.woNo + '/times',
        { woNo: execForm.woNo, worker: 'self', postType: t.postType, workHours: t.hours, workDate: new Date().toISOString().slice(0, 10) })
    }
    ElMessage.success('执行记录已保存')
    execVisible.value = false
    await loadAll()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '保存失败') } finally { saving.value = false }
}
function openSubDialog(row) {
  Object.assign(subForm, { parentWoNo: row.woNo, desc: '' })
  subVisible.value = true
}
async function doCreateSub() {
  if (!subForm.desc) { ElMessage.warning('问题描述必填'); return }
  await act(woSubWorkOrder(subForm.parentWoNo, { desc: subForm.desc }), '子工单已创建')
  subVisible.value = false
}
async function doAccept(row, who) {
  const evalOpts = who === 'ENG' ? ['GOOD', 'OK', 'BAD'] : []
  let resultEval
  if (evalOpts.length) {
    const { value } = await ElMessageBox.prompt('维护结果评价（GOOD/OK/BAD）', `验收 ${row.woNo}`,
      { inputValue: 'GOOD', inputPattern: /GOOD|OK|BAD/, inputErrorMessage: 'GOOD/OK/BAD' })
    resultEval = value
  }
  await act(woAccept(row.woNo, who, resultEval), who === 'OP' ? '操作员已验收' : '工程师已验收')
}
async function doClose(row) {
  await ElMessageBox.confirm(`关闭工单 ${row.woNo}？将执行 BR-20 四前置校验并归集成本`, '提示', { type: 'warning' })
  await act(woClose(row.woNo), '工单已关闭，成本已归集')
}

// ================= 报修 =================
async function doSubmitReport() {
  if (!reportForm.cardId || !reportForm.faultSymptom || !reportForm.faultTime) {
    ElMessage.warning('卡片/故障现象/发现时间必填'); return
  }
  saving.value = true
  try {
    await submitReport({ ...reportForm })
    ElMessage.success('报修已提交' + (reportForm.safetyRisk === '1' ? '（已自动安全挂锁）' : ''))
    reportVisible.value = false
    Object.assign(reportForm, { cardId: '', faultSymptom: '', faultTime: '', impactScope: '', detail: '', safetyRisk: '0' })
    await loadReports()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '提交失败') } finally { saving.value = false }
}
function openConvertDialog(row) {
  Object.assign(convertForm, { id: row.id, reportNo: row.reportNo, faultSymptom: row.faultSymptom,
    assignee: '', resolvePlan: 'SELF', outsourceVendor: '' })
  convertVisible.value = true
}
async function doConvert() {
  if (!convertForm.assignee) { ElMessage.warning('须指派维修人员'); return }
  saving.value = true
  try {
    await convertReport(convertForm.id, { assignee: convertForm.assignee, resolvePlan: convertForm.resolvePlan,
      outsourceVendor: convertForm.outsourceVendor })
    ElMessage.success('已转维修工单')
    convertVisible.value = false
    await loadReports()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '转工单失败（检查挂锁/评估冻结）') } finally { saving.value = false }
}
async function doRejectReport(row) {
  const { value } = await ElMessageBox.prompt('驳回原因', `驳回 ${row.reportNo}`, { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await act(rejectReport(row.id, value), '已驳回')
}

// ================= 挂锁 =================
async function doLock() {
  if (!lockForm.cardId || !lockForm.reason) { ElMessage.warning('卡片与原因必填'); return }
  await act(lockAsset({ cardId: lockForm.cardId, reason: lockForm.reason }), '挂锁成功')
  lockForm.reason = ''
}
async function doRelease(row) {
  const { value } = await ElMessageBox.prompt('解除原因', `解锁 ${row.lockNo}（须设备主管）`, { inputValue: '排查完成' })
  await act(releaseLock(row.id, value), '已解锁')
}

// ================= 履历 =================
async function loadHistory() {
  if (!historyCardId.value) { ElMessage.warning('输入卡片 ID'); return }
  try {
    const [h, s] = await Promise.all([
      maintenanceHistory(historyCardId.value),
      maintenanceStats(historyCardId.value, 12)
    ])
    Object.assign(history, h.data, { stats: s.data })
    historyVisible.value = true
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '履历加载失败') }
}

// ================= 备件 =================
async function doRequestSpares() {
  const lines = spareLines.value.filter(l => l.itemCode && l.qty > 0)
  if (!lines.length) { ElMessage.warning('至少一行有效明细'); return }
  saving.value = true
  try {
    await woSpares(spareWoNo.value, { lines, overReason: spareOverReason.value })
    ElMessage.success('领料单已生成（DRAFT，待仓库过账）')
    spareVisible.value = false
    spareOverReason.value = ''
    await loadWoIssues()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '领料失败（超领须填原因 BR-17）') } finally { saving.value = false }
}
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.spacer { flex: 1; }
.muted { color: #909399; font-size: 12px; }
.gen-result { margin-top: 4px; }
.stat-row { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
