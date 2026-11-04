<template>
  <div>

    <!-- ==================== 14.4.2 列级权限 ==================== -->

    <div class="toolbar">
      <el-button size="small" type="success" @click="sensVisible = true">新增登记位</el-button>
      <el-button size="small" @click="loadSensitive">刷新</el-button>
      <PageInfo class="page-info--inline" title="敏感字段登记位（COL_SENSITIVE）：登记后未命中掩码规则的字段默认掩码（C-4.10-02）；变更走数据治理审批链" />
    </div>
    <el-table :data="sensitiveFields" border stripe size="small" max-height="300">
      <el-table-column prop="targetKey" label="字段" min-width="160" />
      <el-table-column prop="roleCode" label="适用角色（空=全体）" width="160" />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success'
            : row.status === 'PENDING_APPROVAL' ? 'warning' : 'info'" size="small">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="180" align="center">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT' || row.status === 'DISABLED'"
                     size="small" type="success" link @click="onSubmitSens(row)">提交审批</el-button>
          <el-button v-if="row.status === 'ENABLED'"
                     size="small" type="danger" link @click="onDisableSens(row)">停用</el-button>
          <el-button size="small" type="primary" link @click="showSensLogs(row)">历史</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4 style="margin-top: 14px">权限校验审计日志（FR-4.10-3-5，≥365 天逐次留痕）</h4>
    <el-table :data="auditRows" border stripe size="small" max-height="200">
      <el-table-column prop="USER_NAME" label="用户" width="120" />
      <el-table-column prop="API_PATH" label="接口" min-width="200" show-overflow-tooltip />
      <el-table-column prop="MASK_COLS" label="掩码字段" width="140" show-overflow-tooltip />
      <el-table-column prop="ROW_COUNT" label="行数" width="80" align="center" />
      <el-table-column prop="CREATE_DATE" label="时间" width="160" />
    </el-table>

    <!-- 敏感字段新增对话框 -->
    <el-dialog v-model="sensVisible" title="新增敏感字段登记位" width="480px">
      <el-form label-width="100px" size="small">
        <el-form-item label="字段名">
          <el-input v-model="sensForm.targetKey" placeholder="如 phone / id_card / salary" />
        </el-form-item>
        <el-form-item label="适用角色">
          <el-input v-model="sensForm.roleCode" placeholder="留空 = 全体用户" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="sensForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sensVisible = false">取消</el-button>
        <el-button type="primary" @click="onSaveSens">保存草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 14.4.2 列级权限（拆分自原权限控制操作台）：敏感字段登记位 CRUD / 审批 / 变更历史 / 校验审计日志。
 * 三页逻辑互不共享，全部留在页内（design D2：单 tab 独有逻辑不强抽）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getSensitiveFieldsApi, createSensitiveFieldApi, submitSensitiveFieldApi,
  disableSensitiveFieldApi, getSensitiveFieldLogsApi
} from '@/api/bi/perm-control'
import request from '@/utils/request'

const sensitiveFields = ref([])
const auditRows = ref([])
const sensVisible = ref(false)
const sensForm = ref({ targetKey: '', roleCode: '', remark: '' })

async function loadSensitive() {
  const res = await getSensitiveFieldsApi()
  sensitiveFields.value = res.data || []
  // 审计日志（QUERY 逐次留痕，FR-4.10-3-5，管理视角）
  try {
    const a = await request.get('/bi/row-perms/audit', { params: { size: 50 } })
    auditRows.value = a.data || []
  } catch (e) {
    auditRows.value = []
  }
}

async function onSaveSens() {
  try {
    await createSensitiveFieldApi(sensForm.value)
    ElMessage.success('已保存草稿，提交审批后生效')
    sensVisible.value = false
    sensForm.value = { targetKey: '', roleCode: '', remark: '' }
    loadSensitive()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  }
}

async function onSubmitSens(row) {
  try {
    await submitSensitiveFieldApi(row.id)
    ElMessage.success('已提交审批')
    loadSensitive()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  }
}

async function onDisableSens(row) {
  await disableSensitiveFieldApi(row.id)
  ElMessage.success('已停用')
  loadSensitive()
}

async function showSensLogs(row) {
  const res = await getSensitiveFieldLogsApi(row.id)
  const lines = (res.data || []).map(l =>
    `${l.opAt} ${l.operator} ${l.action}: ${l.oldVal || '∅'} → ${l.newVal || '∅'}`)
  await ElMessageBox.alert(lines.join('\n') || '暂无变更历史', '变更历史', { customStyle: { whiteSpace: 'pre-wrap' } })
}

onMounted(() => {
  loadSensitive()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
h4 {
  margin: 10px 0 6px;
}
</style>
