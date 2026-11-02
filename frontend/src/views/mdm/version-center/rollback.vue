<template>
  <div class="page">
    <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
              title="回滚将把所选历史版本快照生成为新版本 V(N+1) 并直接生效（审计留痕+事件同步下游），历史版本不受影响" />
    <el-form inline>
      <el-form-item label="主数据类型">
        <el-select v-model="bizType" style="width: 160px" @change="onTypeChange">
          <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="实体">
        <el-select v-model="entityId" filterable remote reserve-keyword clearable
                   :remote-method="searchEntities" :loading="entityLoading"
                   style="width: 280px" placeholder="输入编码/名称搜索" @change="loadVersions">
          <el-option v-for="e in entities" :key="e.id" :label="`${e.code} ${e.name || ''}`" :value="e.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <el-table v-if="versions.length" :data="versions" border size="small" @row-click="selectRollback">
      <el-table-column prop="versionNo" label="版本" width="80">
        <template #default="{ row }">V{{ row.versionNo }}</template>
      </el-table-column>
      <el-table-column prop="opType" label="操作" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="opTypeTag(row.opType)">{{ opTypeLabel(row.opType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="changeReason" label="变更原因" />
      <el-table-column prop="createBy" label="操作人" width="120" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button type="warning" size="small" :disabled="!isAdmin" @click="selectRollback(row)">回滚到此版本</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="rollbackVisible" title="确认回滚" width="480px">
      <el-form label-width="90px">
        <el-form-item label="目标版本">
          <el-tag type="warning">V{{ rollbackTarget }}</el-tag>
          <span style="margin-left: 8px; color: #909399">将生成 V(N+1) 并直接生效</span>
        </el-form-item>
        <el-form-item label="回滚原因" required>
          <el-input v-model="rollbackReason" type="textarea" :rows="3" placeholder="至少 2 字，将写入审计与版本记录" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rollbackVisible = false">取消</el-button>
        <el-button type="warning" :loading="rollbackLoading" @click="doRollback">确认回滚</el-button>
      </template>
    </el-dialog>
    <el-empty v-if="!versions.length && entityId" description="无版本记录" />
  </div>
</template>

<script setup>
/**
 * 1.7.3 版本回滚（拆分自原版本中心操作台，ADMIN）：选历史版本生成 V(N+1)。
 * 实体与版本共享状态见 useVersionCenter.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rollbackApi } from '@/api/mdm/version-sync'
import { useVersionCenter, TYPES } from './useVersionCenter'
import { opTypeLabel, opTypeTag } from './vc-helpers'

const { bizType, entityId, entities, entityLoading, versions,
  searchEntities, onTypeChange, loadVersions } = useVersionCenter()

const isAdmin = ref(false)
const rollbackVisible = ref(false)
const rollbackTarget = ref(null)
const rollbackReason = ref('')
const rollbackLoading = ref(false)

onMounted(() => {
  try {
    const roles = JSON.parse(localStorage.getItem('roles') || '[]')
    isAdmin.value = roles.includes('ROLE_ADMIN') || roles.includes('ADMIN')
  } catch (e) { isAdmin.value = false }
  searchEntities('')
})

function selectRollback(row) {
  if (row.versionNo === versions.value[0]?.versionNo) {
    ElMessage.warning('已是最新版本，无需回滚')
    return
  }
  rollbackTarget.value = row.versionNo
  rollbackReason.value = ''
  rollbackVisible.value = true
}

async function doRollback() {
  if (!rollbackReason.value || rollbackReason.value.trim().length < 2) {
    ElMessage.warning('回滚原因必填（至少 2 字）')
    return
  }
  await ElMessageBox.confirm(
    `确认回滚至 V${rollbackTarget}？将生成新版本 V(N+1) 并直接生效`, '提示', { type: 'warning' })
  rollbackLoading.value = true
  try {
    const r = await rollbackApi(bizType.value, entityId.value, rollbackTarget.value, rollbackReason.value.trim())
    ElMessage.success(`回滚成功，已生成 V${r.data.newVersionNo}`)
    rollbackVisible.value = false
    loadVersions()
  } finally {
    rollbackLoading.value = false
  }
}
</script>
