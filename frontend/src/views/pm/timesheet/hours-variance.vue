<template>
  <div class="hours-variance">
    <el-card style="margin-bottom: 12px;">
      <div class="toolbar">
        <el-select v-model="projectId" placeholder="按项目过滤（留空=全部 ACTIVE）" filterable clearable
          style="width: 320px;" @change="load">
          <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="p.name" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
      <div class="hint">
        偏差率 =（实际累计 − 计划累计）/ 计划累计 × 100%；>10% 黄色、>20% 红色（BR-4.8-19）。
        预警后 5 个工作日内请点「已处理」（可附说明），超期自动升级 PMO。
      </div>
    </el-card>

    <!-- 项目偏差 -->
    <el-card style="margin-bottom: 12px;">
      <template #header><b>项目偏差</b></template>
      <el-table :data="board.projects || []" v-loading="loading" stripe
        :row-class-name="rowClass">
        <el-table-column prop="projectNo" label="项目号" width="140" />
        <el-table-column prop="name" label="项目" min-width="180" show-overflow-tooltip />
        <el-table-column prop="planHours" label="计划工时" width="100" :precision="1" />
        <el-table-column prop="actualHours" label="实际工时" width="100" :precision="1" />
        <el-table-column label="偏差率" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.level === 'RED'" type="danger" effect="dark" size="small">
              {{ row.deviationRate }}% 红色
            </el-tag>
            <el-tag v-else-if="row.level === 'YELLOW'" type="warning" size="small">
              {{ row.deviationRate }}% 黄色
            </el-tag>
            <span v-else-if="row.deviationRate !== null" style="color: #67c23a;">{{ row.deviationRate }}%</span>
            <span v-else style="color: #909399;">无预估</span>
          </template>
        </el-table-column>
        <el-table-column prop="pmUserId" label="项目经理" width="120" />
      </el-table>
    </el-card>

    <!-- 预警与处理 -->
    <el-card>
      <template #header><b>偏差预警</b></template>
      <el-table :data="board.alerts || []" v-loading="loading" stripe>
        <el-table-column prop="alertDate" label="预警日" width="110" />
        <el-table-column prop="projectName" label="项目" min-width="180" show-overflow-tooltip />
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="row.level === 'RED' ? 'danger' : 'warning'" size="small">
              {{ row.level === 'RED' ? '红色' : '黄色' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deviationRate" label="偏差率(%)" width="100" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.escalatedFlag === '1'" type="danger" effect="dark" size="small">已升级 PMO</el-tag>
            <el-tag v-else-if="row.handledFlag === '1'" type="success" size="small">已处理</el-tag>
            <el-tag v-else type="info" size="small">待处理</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="handledNote" label="处理说明" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.handledFlag !== '1'" link type="primary" @click="handle(row)">已处理</el-button>
            <span v-else style="color: #909399;">{{ row.handledBy }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { varianceList, handleAlert, pmProjects } from '@/api/pm/timesheet'

const loading = ref(false)
const projects = ref([])
const projectId = ref('')
const board = ref({})

onMounted(async () => {
  const { data } = await pmProjects()
  projects.value = data || []
  load()
})

async function load() {
  loading.value = true
  try {
    const { data } = await varianceList(projectId.value || undefined)
    board.value = data || {}
  } finally {
    loading.value = false
  }
}

async function handle(row) {
  let note
  try {
    const { value } = await ElMessageBox.prompt('处理说明（可选，如「已调整后续估算/增加资源」）', '标记已处理', {
      inputPlaceholder: '选填'
    })
    note = value
  } catch { return }
  try {
    await handleAlert(row.id, note)
    ElMessage.success('已标记处理（不再升级）')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

function rowClass({ row }) {
  return row.level === 'RED' ? 'row-red' : row.level === 'YELLOW' ? 'row-yellow' : ''
}
</script>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; }
.hint { color: #909399; font-size: 12px; margin-top: 8px; line-height: 1.7; }
:deep(.row-red) { --el-table-tr-bg-color: #fef0f0; }
:deep(.row-yellow) { --el-table-tr-bg-color: #fdf6ec; }
</style>
