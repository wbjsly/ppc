<template>
  <!-- 详情/签署对话框（16.5.1 紧急申请 / 16.5.2 双人审批 两页共用） -->
  <el-dialog v-model="detailDlg" :title="'Break-Glass 申请详情'" width="760px">
    <template v-if="detail">
      <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="被授权人">{{ detail.targetUsername }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ stLabel(detail.request.status) }}</el-descriptions-item>
        <el-descriptions-item label="时长">{{ detail.request.durationHours }} 小时</el-descriptions-item>
        <el-descriptions-item label="录屏声明">{{ detail.request.screenDeclare === 'YES' ? '需要' : '不需要' }}</el-descriptions-item>
        <el-descriptions-item label="处置说明" :span="2">{{ detail.request.reason }}</el-descriptions-item>
      </el-descriptions>
      <h4>审批链（双人会签）</h4>
      <el-table :data="logTasks" border size="small">
        <el-table-column prop="nodeName" label="节点" width="200" />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="taskType(row.status)" size="small">{{ taskLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="signerName" label="签署人" width="120">
          <template #default="{ row }">{{ row.signerName || row.signer || '—' }}</template>
        </el-table-column>
        <el-table-column prop="opinion" label="意见" min-width="140">
          <template #default="{ row }">{{ row.opinion || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <template v-if="row.status === 'ACTIVE'">
              <el-button link type="success" @click="signTask(row, true)">通过</el-button>
              <el-button link type="danger" @click="signTask(row, false)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * Break-Glass 申请详情/签署弹窗（跨页共用，design D2：状态放 useBreakGlass.js 单例）。
 * 供紧急申请（详情/签署）与双人审批（审批）两页渲染，双人会签署逻辑不变。
 */
import { useBreakGlass } from '../useBreakGlass'
import { stLabel, taskType, taskLabel } from '../bg-helpers'

const { detailDlg, detail, logTasks, signTask } = useBreakGlass()
</script>

<style scoped>
h4 { margin: 14px 0 8px; }
</style>
