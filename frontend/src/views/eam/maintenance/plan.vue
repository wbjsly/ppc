<template>
  <div class="eam-page">

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openPlanDialog()">新建计划</el-button>
        <el-button type="success" :loading="generating" @click="doGenerate">生成计划工单</el-button>
        <span class="spacer" />
        <el-button plain size="small" @click="loadPlans">刷新</el-button>
        <PageInfo class="page-info--inline" title="点检保养：维护计划 → 批量生成计划工单（幂等/停机顺延）→ 执行 → 关闭成本归集" description="关键设备计划须设备主管审批；人工成本 = 工时 × SysParam 岗位费率（缺参标待调整，不阻断关闭）。" />
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

    <!-- 计划对话框 -->
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

    <ExecDialog />
  </div>
</template>

<script setup>
/**
 * 7.3.1 点检保养（拆分自原运维管理操作台）：维护计划 CRUD/审批、计划工单生成与执行闭环。
 * 共享状态与工单动作见 useMaintenance.js；执行记录对话框为两页共用 _parts/ExecDialog.vue。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import {
  loading, saving, generating, planRows, planWoRows, planVisible, planForm, genResult,
  setRefresh, loadPlans, openPlanDialog, doSavePlan, doSubmitApproval, doDisablePlan,
  describeGen, doGenerate, fmt, cycleLabel, statusLabel, statusTag,
  approvalLabel, approvalTag, doAssign, openExecDialog, doAccept, doClose,
  act, woStart, woSubmitAcceptance
} from './useMaintenance'
import ExecDialog from './_parts/ExecDialog.vue'

onMounted(() => {
  setRefresh(loadPlans)
  loadPlans()
})
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.spacer { flex: 1; }
.muted { color: #909399; font-size: 12px; }
.gen-result { margin-top: 4px; }
</style>
