<template>
  <div class="page">
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
    <el-timeline v-if="versions.length">
      <el-timeline-item v-for="v in versions" :key="v.id" :timestamp="`V${v.versionNo} · ${formatTime(v.createDate)}`"
                        :type="v.opType === 'ROLLBACK' ? 'warning' : 'primary'">
        <el-card shadow="never">
          <div>
            <el-tag size="small" :type="opTypeTag(v.opType)">{{ opTypeLabel(v.opType) }}</el-tag>
            <el-tag v-if="v.changeType" size="small" style="margin-left: 6px"
                    :type="v.changeType === 'CRITICAL' ? 'danger' : 'info'">
              {{ v.changeType === 'CRITICAL' ? '关键属性' : '一般属性' }}
            </el-tag>
            <span style="margin-left: 10px; color: #909399">{{ v.createBy }} · 快照 {{ v.snapshotSize }}B</span>
          </div>
          <div v-if="v.changeReason" style="margin-top: 6px">变更原因：{{ v.changeReason }}</div>
          <el-button size="small" style="margin-top: 8px" @click="viewSnapshot(v.versionNo)">查看快照</el-button>
        </el-card>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else-if="entityId" description="无版本记录" />

    <el-drawer v-model="snapshotVisible" title="版本快照" size="55%">
      <pre class="snapshot-pre">{{ snapshotJson }}</pre>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 1.7.2 历史回溯（拆分自原版本中心操作台）：版本时间线与快照查看。
 * 实体与版本共享状态见 useVersionCenter.js。
 */
import { ref, onMounted } from 'vue'
import { getSnapshotApi } from '@/api/mdm/version-sync'
import { useVersionCenter, TYPES } from './useVersionCenter'
import { opTypeLabel, opTypeTag } from './vc-helpers'

const { bizType, entityId, entities, entityLoading, versions,
  searchEntities, onTypeChange, loadVersions } = useVersionCenter()

const snapshotVisible = ref(false)
const snapshotJson = ref('')

async function viewSnapshot(v) {
  const r = await getSnapshotApi(bizType.value, entityId.value, v)
  try {
    snapshotJson.value = JSON.stringify(JSON.parse(r.data.snapshotJson), null, 2)
  } catch (e) {
    snapshotJson.value = r.data.snapshotJson || ''
  }
  snapshotVisible.value = true
}

function formatTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }

onMounted(() => searchEntities(''))
</script>

<style scoped>
.snapshot-pre { background: #f5f7fa; padding: 12px; border-radius: 4px; max-height: 70vh; overflow: auto; font-size: 12px; }
</style>
