<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">评分录入（10.10.2）</span>
        <div>
          <el-select v-model="planId" placeholder="选择方案" clearable style="width:220px;margin-right:8px" @change="load">
            <el-option v-for="p in plans" :key="p.id" :label="`${p.period} ${p.name}`" :value="p.id" />
          </el-select>
          <el-button type="primary" @click="openEnter">录入评分</el-button>
          <el-button :disabled="!sel.length" @click="doBatchSubmit">批量提交（{{ sel.length }}）</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="scores" height="440" @selection-change="s => sel = s">
        <el-table-column type="selection" width="40" :selectable="r => r.status === 'DRAFT'" />
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="empName" label="姓名" width="100" />
        <el-table-column prop="score" label="分值" width="70" />
        <el-table-column prop="grade" label="等级" width="70" />
        <el-table-column prop="coef" label="系数" width="70" />
        <el-table-column prop="commentText" label="评语" min-width="150" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="{ DRAFT: 'info', SUBMITTED: 'warning', CALIBRATING: 'warning', CALIBRATED: 'primary', CONFIRMED: 'success' }[row.status]">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small" @click="doSubmit(row)">提交</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="enterVisible" title="录入评分" width="420px">
      <el-form label-width="80px">
        <el-form-item label="员工ID"><el-input v-model="form.empId" placeholder="hr_employee.ID" /></el-form-item>
        <el-form-item label="分值">
          <el-input-number v-model="form.score" :min="0" :max="100" :step="1" />
          <span style="margin-left:8px;color:#999">留空则直接指定等级</span>
        </el-form-item>
        <el-form-item label="等级">
          <el-select v-model="form.grade" clearable placeholder="自动映射" style="width:120px">
            <el-option v-for="g in ['A','B','C','D']" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="评语"><el-input v-model="form.commentText" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="enterVisible = false">取消</el-button>
        <el-button type="primary" @click="doEnter">录入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { planList, scoreList, enterScore, submitScore, batchSubmit } from '@/api/hr/performance'

const plans = ref([])
const planId = ref('')
const scores = ref([])
const sel = ref([])
const enterVisible = ref(false)
const form = ref({ empId: '', score: null, grade: '', commentText: '' })

async function load() {
  const p = await planList({ status: 'ACTIVE' })
  plans.value = p.data || []
  const s = await scoreList(planId.value ? { planId: planId.value } : {})
  scores.value = s.data || []
}
function openEnter() {
  if (!planId.value) return ElMessage.warning('请先选择方案')
  form.value = { empId: '', score: null, grade: '', commentText: '' }
  enterVisible.value = true
}
async function doEnter() {
  if (!form.value.empId) return ElMessage.warning('员工ID必填')
  try {
    await enterScore({ ...form.value, planId: planId.value })
    ElMessage.success('已录入'); enterVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function doSubmit(row) {
  try {
    await submitScore(row.id)
    ElMessage.success('已提交'); load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function doBatchSubmit() {
  const out = await batchSubmit(sel.value.map(r => r.id))
  ElMessage.success(`提交 ${out.data?.submitted} 条`)
  if (out.data?.errors?.length) ElMessage.warning(out.data.errors.join('；'))
  load()
}
onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
