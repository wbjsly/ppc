<template>
  <div class="adhoc-wrap">
    <!-- ========== 14.6.3 临时报表 ========== -->
    <el-card shadow="never">
      <template #header><span class="card-title">临时报表（我的 + 共享给我的）</span></template>
      <el-table :data="tempReports" size="small">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="relation" label="关系" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.relation === 'mine' ? 'primary' : 'success'">
              {{ row.relation === 'mine' ? '我创建' : '共享给我' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="shareCount" label="共享数" width="80" />
        <el-table-column prop="daysLeft" label="剩余天" width="80" />
        <el-table-column prop="expireAt" label="到期时间" width="160">
          <template #default="{ row }">{{ (row.expireAt || '').slice(0, 10) }}</template>
        </el-table-column>
        <el-table-column label="转正" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.promotedTo" type="success" size="small">已转正</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTemp(row)">打开</el-button>
            <el-button v-if="row.relation === 'mine' && !row.promotedTo" link size="small"
                       @click="openShare(row)">共享</el-button>
            <el-button v-if="row.relation === 'mine' && !row.promotedTo" link type="warning" size="small"
                       @click="doPromote(row)">转正</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 共享弹窗 -->
    <el-dialog v-model="shareVisible" title="共享设置" width="440px">
      <div v-for="(s, i) in shareList" :key="i" class="filter-row">
        <el-select v-model="s.type" style="width:100px">
          <el-option value="user" label="用户" /><el-option value="role" label="角色" />
        </el-select>
        <el-input v-model="s.code" placeholder="用户名或角色码" style="width:200px" />
        <el-button link type="danger" @click="shareList.splice(i, 1)">删</el-button>
      </div>
      <el-button size="small" @click="shareList.push({ type: 'user', code: '' })">+ 授权</el-button>
      <template #footer>
        <el-button @click="shareVisible = false">取消</el-button>
        <el-button type="primary" @click="doShare">保存</el-button>
      </template>
    </el-dialog>

    <!-- 结果抽屉（打开临时报表，跨页共用） -->
    <ResultDrawer ref="drawerRef" />
  </div>
</template>

<script setup>
/**
 * 14.6.3 临时报表（拆分自原即席查询操作台）：列表 / 共享设置 / 转正。
 * 列表状态见 useTempReports.js（即席查询页另存后刷新），结果抽屉见 _parts/ResultDrawer.vue。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  openTempApi, shareTempApi, promoteTempApi
} from '@/api/bi/adhoc-query'
import { useTempReports } from './useTempReports'
import ResultDrawer from './_parts/ResultDrawer.vue'

const { tempReports, loadTemp } = useTempReports()
const drawerRef = ref(null)

const shareVisible = ref(false)
const shareList = ref([])
const shareRow = ref(null)

async function openTemp(row) {
  const res = await openTempApi(row.id, 1, 200)
  const d = res.data || {}
  drawerRef.value.open({
    title: d.name || row.name,
    headers: d.headers || [],
    rows: d.rows || []
  })
}

function openShare(row) {
  shareRow.value = row
  try { shareList.value = JSON.parse(row.shareJson || '[]') } catch (e) { shareList.value = [] }
  if (!shareList.value.length) shareList.value = [{ type: 'user', code: '' }]
  shareVisible.value = true
}

async function doShare() {
  await shareTempApi(shareRow.value.id, shareList.value.filter(s => s.code))
  ElMessage.success('共享已更新')
  shareVisible.value = false
  loadTemp()
}

async function doPromote(row) {
  await ElMessageBox.confirm('转正将生成正式模板草稿并执行口径一致性校验，继续？', '转正')
  try {
    const res = await promoteTempApi(row.id)
    ElMessage.success('已转正，模板草稿 ID：' + (res.data?.templateId || ''))
    loadTemp()
  } catch (e) {
    const msg = e?.response?.data?.message || '转正失败（口径校验未通过）'
    ElMessage.error(msg)
  }
}

onMounted(() => {
  loadTemp()
})
</script>

<style scoped>
.adhoc-wrap { padding: 4px; }
.card-title { font-weight: 600; }
.filter-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
</style>
