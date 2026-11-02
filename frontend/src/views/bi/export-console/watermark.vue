<template>
  <div>
    <el-page-header content="数据导出（14.5）— 模板/数据集双源、三格式水印（Excel 页眉/PDF 斜置/CSV BOM）、AES-256 加密下载 7 天有效、超量分级审批（经理/总监）"
                    style="margin-bottom: 8px" />

    <!-- ==================== 14.5.2 水印标记 ==================== -->
    <el-alert type="info" show-icon :closable="false" style="margin-bottom: 8px"
              title="导出文件自动嵌入水印：内容 = {用户名}|{时间戳}|{唯一流水号}；Excel 页眉页脚+背景半透明、PDF 每页右下角+斜置、CSV BOM+首行注释（FR-4.10-4-4）" />
    <el-table :data="tasks.filter(t => t.watermark)" border stripe size="small" max-height="320">
      <el-table-column prop="taskNo" label="任务号" width="140" />
      <el-table-column prop="userName" label="用户" width="110" />
      <el-table-column prop="watermark" label="水印内容" min-width="280" show-overflow-tooltip />
      <el-table-column prop="reqId" label="流水号" width="160" />
    </el-table>
    <el-empty v-if="!tasks.some(t => t.watermark)" description="暂无已完成导出（水印随任务完成生成）" />
  </div>
</template>

<script setup>
/**
 * 14.5.2 水印标记（拆分自原数据导出操作台）：已完成导出任务的水印明细。
 * 任务列表见 useExportConsole.js（导出申请页提交后刷新同一份数据）。
 */
import { onMounted } from 'vue'
import { useExportConsole } from './useExportConsole'

const { tasks, loadTasks } = useExportConsole()

onMounted(loadTasks)
</script>
