<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      title="备件领用（7.3.3）：关联工单领料，超清单须说明（BR-17）；走库存 MAINT 领料单扣减"
      description="关闭前置（BR-20）：未过账领料/未回填工时/未闭子工单/外协未传验收单 → 阻断并列清单。" />

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

    <!-- 发起领料对话框 -->
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
  </div>
</template>

<script setup>
/**
 * 7.3.3 备件领用（拆分自原运维管理操作台）：按工单查询领料行、发起 MAINT 领料。
 * 共享状态见 useMaintenance.js。
 */
import { onMounted } from 'vue'
import {
  saving, spareWoNo, issueRows, spareVisible, spareLines, spareOverReason,
  setRefresh, loadWoIssues, doRequestSpares
} from './useMaintenance'

onMounted(() => {
  setRefresh(loadWoIssues)
})
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.spacer { flex: 1; }
</style>
