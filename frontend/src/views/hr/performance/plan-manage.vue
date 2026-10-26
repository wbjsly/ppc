<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">绩效方案（10.10.1）</span>
        <div>
          <el-button type="primary" @click="openCreate">新建方案</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="plans" height="420">
        <el-table-column prop="planNo" label="方案号" width="160" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="periodType" label="周期类型" width="90" />
        <el-table-column prop="period" label="周期" width="90" />
        <el-table-column label="等级定义" min-width="260">
          <template #default="{ row }">
            <el-tag v-for="g in parseGrades(row.gradeJson)" :key="g.grade" size="small" style="margin-right:4px">
              {{ g.grade }}×{{ g.coef }}（≥{{ g.minScore }}）
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="{ ACTIVE: 'success', DRAFT: 'info', CLOSED: '', VOID: 'danger' }[row.status]" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status === 'ACTIVE' || row.status === 'DRAFT'" link type="danger" size="small"
                       @click="doVoid(row)">作废</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="新建绩效方案" width="460px">
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                title="等级定义留空则取 SysParam 默认（A=1.2/B=1.0/C=0.8/D=0.6）；同周期同部门唯一生效" />
      <el-form label-width="90px">
        <el-form-item label="方案名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="周期类型">
          <el-select v-model="form.periodType" style="width:100%">
            <el-option label="年度" value="YEAR" />
            <el-option label="半年" value="HALF" />
            <el-option label="月度" value="MONTH" />
          </el-select>
        </el-form-item>
        <el-form-item label="周期">
          <el-input v-model="form.period" placeholder="如 2026H2 / 2026-12" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { planList, createPlan, voidPlan } from '@/api/hr/performance'

const plans = ref([])
const createVisible = ref(false)
const form = ref({ name: '', periodType: 'HALF', period: '' })

const parseGrades = (json) => {
  try { return JSON.parse(json) || [] } catch { return [] }
}

async function load() {
  const { data } = await planList({})
  plans.value = data || []
}
function openCreate() {
  form.value = { name: '', periodType: 'HALF', period: '' }
  createVisible.value = true
}
async function doCreate() {
  if (!form.value.name || !form.value.period) return ElMessage.warning('名称与周期必填')
  try {
    await createPlan(form.value)
    ElMessage.success('方案已创建'); createVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function doVoid(row) {
  await ElMessageBox.confirm(`作废方案 ${row.planNo}？`, '确认', { type: 'warning' })
  try {
    await voidPlan(row.id)
    ElMessage.success('已作废'); load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
