<template>
  <!-- 申请详情抽屉（16.3.1 权限申请 / 16.4.2 窗口外升级 / 16.4.3 双人复核 三页共用） -->
  <el-drawer v-model="detailDlg" title="申请单详情" size="620px">
    <template v-if="detail">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="类型">{{ detail.request.kind === 'EXCEPTION' ? '例外' : '常规' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabel(detail.request.status) }}</el-descriptions-item>
        <el-descriptions-item label="被授权人">{{ detail.targetUsername }}</el-descriptions-item>
        <el-descriptions-item label="风险">{{ riskLabel(detail.request.riskLevel) }}</el-descriptions-item>
        <el-descriptions-item label="目标角色" :span="2">{{ fmtRoles(detail.request.rolesJson) }}</el-descriptions-item>
        <el-descriptions-item label="数据范围" :span="2">{{ detail.request.dataScope }}</el-descriptions-item>
        <el-descriptions-item label="理由" :span="2">{{ detail.request.reason }}</el-descriptions-item>
        <el-descriptions-item label="有效期">{{ detail.request.validMonths }} 个月</el-descriptions-item>
        <el-descriptions-item label="生效策略">{{ detail.request.activationMode === 'SCHEDULED' ? '指定时点' : '立即' }}</el-descriptions-item>
      </el-descriptions>

      <h4>提交时预检快照</h4>
      <el-alert v-if="!snapConflicts.length && !snapBeyond.length" type="success" :closable="false"
        show-icon title="无超基线、无互斥冲突" />
      <template v-else>
        <el-alert v-if="snapBeyond.length" type="warning" :closable="false" show-icon
          :title="'超基线：' + snapBeyond.join('、')" style="margin-bottom: 6px" />
        <el-table v-if="snapConflicts.length" :data="snapConflicts" size="small">
          <el-table-column prop="roleA" label="互斥 A" />
          <el-table-column prop="roleB" label="互斥 B" />
          <el-table-column prop="remark" label="说明" />
        </el-table>
      </template>

      <h4>审批链与日志</h4>
      <el-table :data="logTasks" size="small" border>
        <el-table-column prop="seq" label="序" width="50" />
        <el-table-column prop="nodeName" label="节点" min-width="130" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="taskType(row.status)">{{ taskLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="signerName" label="签署人" width="90" />
        <el-table-column prop="opinion" label="意见" min-width="90" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <template v-if="row.status === 'ACTIVE'">
              <el-button link type="primary" @click="signTask(row, true)">同意</el-button>
              <el-button link type="danger" @click="signTask(row, false)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <div class="muted" style="margin-top: 8px">
        就地签署对接既有审批端点；按人节点仅指定签署人可签（其他人操作将返回 403）
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
/**
 * 申请单详情抽屉（跨页共用对话框，design D2：状态放 usePermissionRequest.js 单例）。
 * 供权限申请 / 窗口外升级 / 双人复核三页渲染，业务逻辑（就地签署/驳回）不变。
 */
import { usePermissionRequest } from '../usePermissionRequest'
import { fmtRoles, riskLabel, statusLabel, taskType, taskLabel } from '../request-helpers'

const { detailDlg, detail, logTasks, snapConflicts, snapBeyond, signTask } = usePermissionRequest()
</script>

<style scoped>
.muted { color: #909399; font-size: 12px; }
h4 { margin: 14px 0 8px; }
</style>
