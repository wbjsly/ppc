<template>
  <div class="res-release">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        资源释放（FR-4.8-7-6）：归档审批通过时自动生成资源释放清单快照——按参与人汇总项目工时
        （取自工时行数据）。产能日历（每人每日可用工时）为全局资源池、与项目无关联，归档不修改。
        归档后工时/成本/变更/领料/基线五类写入口全域只读（422「项目已归档」）。
        本页只读，无操作按钮（PMO/ADMIN 可见）。
      </div>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <b>资源释放清单（归档快照）</b>
          <div>
            <el-input v-model="projectId" placeholder="项目 ID" style="width: 260px;" clearable />
            <el-button type="primary" style="margin-left: 8px;" @click="load">查询</el-button>
          </div>
        </div>
      </template>

      <el-descriptions v-if="check.id" :column="3" size="small" border style="margin-bottom: 12px;">
        <el-descriptions-item label="项目编号">{{ check.projectNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="归档状态">
          <el-tag :type="check.status === 'FROZEN' ? 'warning' : 'info'" size="small">
            {{ check.status === 'FROZEN' ? '审批中' : '起草中' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="有条件结项">
          {{ check.conditionalFlag === '1' ? '是（含遗留工单）' : '否' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert v-if="check.id && !check.resReleaseJson" type="info" :closable="false" show-icon
        title="释放清单将在归档审批通过时自动生成（按参与人汇总工时快照）。" />
      <template v-else-if="check.resReleaseJson">
        <el-table :data="releaseRows" size="small" border>
          <el-table-column prop="empId" label="参与人工号" width="160" />
          <el-table-column prop="empName" label="姓名" width="140" />
          <el-table-column prop="hours" label="累计工时(h)" width="130" align="right" />
        </el-table>
        <el-alert type="success" :closable="false" show-icon style="margin-top: 12px;"
          title="资源已随归档释放：该项目工时/成本入口已关闭，参与人员可投入新项目。" />
      </template>
      <el-empty v-else description="输入项目 ID 后查询释放清单" />
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/closure'

const projectId = ref('')
const check = ref({})
const releaseRows = ref([])

async function load() {
  if (!projectId.value) return
  const { data } = await api.closureDetail(projectId.value)
  check.value = data.check || {}
  if (!check.value.id) {
    ElMessage.warning('该项目尚无结项检查单（未发起结项）')
    releaseRows.value = []
    return
  }
  try {
    releaseRows.value = check.value.resReleaseJson
      ? JSON.parse(check.value.resReleaseJson) : []
  } catch {
    releaseRows.value = []
  }
}
</script>

<style scoped>
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
