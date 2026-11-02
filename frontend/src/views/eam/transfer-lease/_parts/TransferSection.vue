<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openTrfDialog(mode)">新建调拨</el-button>
        <template v-if="mode === 'dept'">
          <el-button @click="loadPool">闲置共享池</el-button>
        </template>
        <template v-else>
          <el-button @click="doIntercoCheck">C-10 月结卡控</el-button>
        </template>
        <span class="spacer" />
        <el-button plain size="small" @click="loadTrfs(mode)">刷新</el-button>
      </div>

      <div v-if="intercoResult" class="gen-result">
        <el-alert type="success" closable style="margin:8px 0;"
          title="C-4.13-10 月结卡控通过：无未闭环的跨法人内部交易" />
      </div>

      <el-table :data="trfRows" size="small" border v-loading="loading" max-height="360">
        <el-table-column prop="trfNo" label="调拨单号" width="170" />
        <el-table-column prop="cardNo" label="卡片号" width="140" />
        <el-table-column prop="fromDept" label="调出部门" min-width="110" />
        <el-table-column prop="fromCc" label="调出成本中心" width="110" />
        <el-table-column label="→" width="40" align="center">→</el-table-column>
        <el-table-column prop="toDept" label="调入部门" min-width="110" />
        <el-table-column prop="toCc" label="调入成本中心" width="110" />
        <el-table-column v-if="mode === 'cross'" prop="toLe" label="调入法人" width="90" />
        <el-table-column prop="status" label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="trfTag(row.status)" size="small">{{ trfLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="mode === 'cross'" prop="intercoNo" label="内部交易单" width="160">
          <template #default="{ row }">
            <span v-if="row.intercoNo">{{ row.intercoNo }}</span>
            <el-tag v-else type="warning" size="small">待生成</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT' || row.status === 'REJECTED'" link type="primary"
              size="small" @click="doSubmit(row, mode)">提交审批</el-button>
            <template v-if="row.status === 'APPROVED' || row.status === 'HANDOVERED'">
              <el-button link type="warning" size="small" :disabled="!!row.outConfirmBy"
                @click="doHandover(row, 'OUT', mode)">调出确认</el-button>
              <el-button link type="success" size="small" :disabled="!!row.inConfirmBy"
                @click="doHandover(row, 'IN', mode)">调入确认</el-button>
            </template>
            <el-button v-if="row.status === 'HANDOVERED'" link type="danger" size="small"
              @click="doComplete(row, mode)">确认完成</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 闲置共享池（7.4.1） -->
    <el-card v-if="mode === 'dept' && poolRows.length" shadow="never" style="margin-top:12px;">
      <template #header>闲置共享池（≥12 月闲置可发起调拨；≥24 月处置评估待办）</template>
      <el-table :data="poolRows" size="small" border max-height="260">
        <el-table-column prop="cardNo" label="卡片号" width="140" />
        <el-table-column prop="assetName" label="资产名称" min-width="160" />
        <el-table-column prop="deptName" label="使用部门" min-width="110" />
        <el-table-column prop="lastUsedAt" label="最后使用日" width="110" align="center" />
        <el-table-column prop="idleMonths" label="闲置月数" width="90" align="right" />
        <el-table-column label="分级" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="row.idleLevel === 'DISPOSAL_EVAL' ? 'danger' : 'warning'" size="small">
              {{ row.idleLevel === 'DISPOSAL_EVAL' ? '处置评估' : '闲置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTrfDialog(mode, row)">发起调拨</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建调拨对话框 -->
    <el-dialog v-model="trfDlg" title="新建资产调拨申请" width="620px">
      <el-form :model="trfForm" label-width="120px">
        <el-form-item label="资产卡片" required>
          <el-input v-model="trfForm.cardNo" placeholder="卡片号（从共享池带入或手输 ZC 开头卡号）" />
        </el-form-item>
        <el-form-item label="调拨类型">
          <el-radio-group v-model="trfForm.transferType" :disabled="mode === 'cross'">
            <el-radio value="DEPT">部门间</el-radio>
            <el-radio value="CROSS_LE">跨法人</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="调入部门"><el-input v-model="trfForm.toDept" /></el-form-item>
        <el-form-item label="调入成本中心"><el-input v-model="trfForm.toCc" /></el-form-item>
        <el-form-item label="调入存放地点"><el-input v-model="trfForm.toLocation" /></el-form-item>
        <el-form-item v-if="trfForm.transferType === 'CROSS_LE'" label="调入法人" required>
          <el-input v-model="trfForm.toLe" placeholder="LE_CODE，如 LE-002" />
        </el-form-item>
        <el-form-item label="调拨原因" required><el-input v-model="trfForm.reason" type="textarea" /></el-form-item>
        <el-form-item label="拟调拨日期"><el-date-picker v-model="trfForm.planDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="trfDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreateTrf(mode)">创建（BR-21 前置校验）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 资产调拨区块（拆分自原调拨租赁操作台）：部门调拨与法人调拨两页共用，
 * 按 mode（'dept'|'cross'）差异渲染闲置共享池 / C-10 卡控、调入法人与内部交易列。
 */
import {
  loading, saving, trfRows, poolRows, intercoResult, trfDlg, trfForm,
  loadTrfs, loadPool, doIntercoCheck, openTrfDialog, doCreateTrf,
  doSubmit, doHandover, doComplete, trfLabel, trfTag
} from '../useTransferLease'

defineProps({ mode: { type: String, required: true } })
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 4px; margin-bottom: 8px; }
.spacer { flex: 1; }
.gen-result { margin-bottom: 4px; }
</style>
