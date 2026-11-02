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
    <template v-if="entityId">
      <el-form inline>
        <el-form-item label="基准版本">
          <el-select v-model="fromVer" style="width: 120px">
            <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="对比版本">
          <el-select v-model="toVer" style="width: 120px">
            <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :disabled="!fromVer || !toVer || fromVer === toVer" @click="loadDiff">对比</el-button>
      </el-form>
      <el-table :data="diffFields" border size="small">
        <el-table-column prop="field" label="字段" width="220" />
        <el-table-column label="旧值">
          <template #default="{ row }">
            <span :class="{ 'diff-old': row.state !== 'ADDED' }">{{ row.oldVal || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="新值">
          <template #default="{ row }">
            <span :class="{ 'diff-new': row.state !== 'REMOVED' }">{{ row.newVal || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="stateType(row.state)" size="small">{{ stateLabel(row.state) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="diffDone && diffFields.length === 0" description="两版本无字段差异" />
    </template>
  </div>
</template>

<script setup>
/**
 * 1.7.1 版本对比（拆分自原版本中心操作台）：选类型/实体 → 两版本字段级 diff。
 * 实体与版本共享状态见 useVersionCenter.js。
 */
import { ref, onMounted } from 'vue'
import { getDiffApi } from '@/api/mdm/version-sync'
import { useVersionCenter, TYPES } from './useVersionCenter'

const { bizType, entityId, entities, entityLoading, versions, fromVer, toVer,
  searchEntities, onTypeChange, loadVersions } = useVersionCenter()

const diffFields = ref([])
const diffDone = ref(false)

async function loadDiff() {
  const r = await getDiffApi(bizType.value, entityId.value, fromVer.value, toVer.value)
  diffFields.value = r.data.fields || []
  diffDone.value = true
}

function stateType(s) { return s === 'ADDED' ? 'success' : s === 'REMOVED' ? 'danger' : 'warning' }
function stateLabel(s) { return s === 'ADDED' ? '新增值' : s === 'REMOVED' ? '删除值' : '值变化' }

onMounted(() => searchEntities(''))
</script>

<style scoped>
.diff-old { color: #f56c6c; text-decoration: line-through; }
.diff-new { color: #67c23a; }
</style>
