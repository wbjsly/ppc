<template>
  <!-- 13.5.2 冲突解决：宽页（字典序裁决交互） -->
  <div style="padding:16px">
    <h3>冲突解决（BL-4.11-01 字典序：VER_NO &gt; CLIENT_TS &gt; IDEMPOTENT_KEY）</h3>
    <el-table :data="conflicts" size="small" v-loading="loading">
      <el-table-column prop="conflictType" label="类型" width="90" />
      <el-table-column prop="fieldPath" label="业务键" show-overflow-tooltip />
      <el-table-column prop="dictSeqKey" label="裁决键" show-overflow-tooltip />
      <el-table-column label="级别" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.needFinApproval === '1'" size="small" type="danger">L2 财务</el-tag>
          <el-tag v-else size="small" type="info">常规</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <template v-if="row.status !== 'RESOLVED'">
            <el-button size="small" type="success" link @click="resolve(row, 'APPROVE')">采纳</el-button>
            <el-button size="small" type="danger" link @click="resolve(row, 'REJECT')">驳回</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <el-alert v-if="hint" type="info" :closable="false" style="margin-top:8px" :title="hint" />
  </div>
</template>

<script setup>
/**
 * 13.5.2 冲突解决（拆分自原离线同步操作台）：字典序裁决（采纳/驳回）+ 提示。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { syncConflictsApi, syncResolveApi } from '@/api/iot/sync'
import { conflictHint } from '@/offline/conflict'

const conflicts = ref([])
const hint = ref('')
const loading = ref(false)

async function loadConflicts() {
  loading.value = true
  try {
    const r = await syncConflictsApi()
    conflicts.value = r.data || []
  } finally {
    loading.value = false
  }
}

async function resolve(row, decision) {
  try {
    const r = await syncResolveApi(row.id, { decision, remark: decision === 'APPROVE'
      ? '移动端采纳服务端胜者' : '驳回保留待人工' })
    hint.value = conflictHint({ conflictType: row.conflictType,
      needFinApproval: row.needFinApproval })
    ElMessage.success(`裁决完成：${r.data?.status}`)
    loadConflicts()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '裁决失败')
  }
}

onMounted(() => {
  loadConflicts()
})
</script>
