<template>
  <div>
    <el-page-header content="数据导出（14.5）— 模板/数据集双源、三格式水印（Excel 页眉/PDF 斜置/CSV BOM）、AES-256 加密下载 7 天有效、超量分级审批（经理/总监）"
                    style="margin-bottom: 8px" />

    <!-- ==================== 14.5.3 超量审批 ==================== -->
    <PageInfo title="超量导出走 ApprovalEngine 分级单签：≤50000 行部门经理、>50000 行部门总监；签署在审批台完成；月度同一用户超量 ≥5 次自动告警信息安全专员（C-4.10-03）" style="margin-bottom: 8px" />
    <el-table :data="approvals" border stripe size="small" max-height="320">
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
      <el-table-column prop="applyBy" label="申请人" width="110" />
      <el-table-column prop="status" label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'APPROVED' ? 'success'
            : row.status === 'REJECTED' ? 'danger' : 'warning'" size="small">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createDate" label="发起时间" width="160" />
      <el-table-column label="操作" width="120" align="center">
        <template #default>
          <el-button size="small" type="primary" link
                     @click="$router.push('/m/16.5')">去审批台</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 14.5.3 超量审批（拆分自原数据导出操作台）：超量导出审批单列表（签署在审批台完成）。
 * 审批单见 useExportConsole.js（导出申请页提交超量导出后刷新同一份数据）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { useExportConsole } from './useExportConsole'

const { approvals, loadApprovals } = useExportConsole()

onMounted(loadApprovals)
</script>
