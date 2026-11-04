<template>
  <div class="eam-page">

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="reportVisible = true">我要报修（全员可报）</el-button>
        <el-button plain size="small" @click="loadReports">刷新报修单</el-button>
        <span class="spacer" />
        <el-input v-model="historyCardId" placeholder="卡片ID 查设备履历" style="width:220px;" clearable />
        <el-button plain @click="loadHistory">设备履历</el-button>
        <PageInfo class="page-info--inline" title="故障维修：全员报修 → 工程师确认派工（BR-16 挂锁阻断）→ 双验收 → BR-19 大修评估" description="安全停机挂锁解除限设备主管；同故障 12 月 ≥3 次触发大修/更换评估并冻结同类工单（BR-19）。" />
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

    <!-- 报修对话框（全员） -->
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

    <!-- 确认派工对话框 -->
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

    <!-- 子工单对话框 -->
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

    <!-- 设备履历弹窗（6.3） -->
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

    <ExecDialog />
  </div>
</template>

<script setup>
/**
 * 7.3.2 故障维修（拆分自原运维管理操作台）：全员报修、确认派工、维修工单执行闭环、
 * 安全停机挂锁与设备履历。共享状态与工单动作见 useMaintenance.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  saving, reportRows, repairWoRows, reportVisible, reportForm,
  convertVisible, convertForm, subVisible, subForm,
  lockRows, lockForm, historyCardId, historyVisible, history,
  setRefresh, loadReports, loadHistory,
  doSubmitReport, openConvertDialog, doConvert, doRejectReport,
  doLock, doRelease, fmt, statusLabel, statusTag, reportLabel,
  doAssign, openExecDialog, openSubDialog, doAccept, doClose, doCreateSub,
  act, woStart
} from './useMaintenance'
import ExecDialog from './_parts/ExecDialog.vue'

const route = useRoute()
if (route.query.cardId) historyCardId.value = String(route.query.cardId)

onMounted(() => {
  setRefresh(async () => {
    await loadReports()
    if (historyCardId.value) await loadHistory()
  })
  loadReports()
  if (historyCardId.value) loadHistory()
})
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.spacer { flex: 1; }
.muted { color: #909399; font-size: 12px; }
.stat-row { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
