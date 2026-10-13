<template>
  <div class="overhead-pool">
    <el-card shadow="never">
      <div class="head">
        <span class="title">5.11.3 制造费用池</span>
        <el-button type="primary" :disabled="!canWrite" @click="openForm()">录入期次池</el-button>
      </div>
      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
        title="制造费用 = 池总额 × (工单基准 / 当期总基准)；分摊规则未配置默认工时比例（FR-4.5-6-3）。池缺失时制费记 0 并标记，阻断工单关闭。" />
      <el-table :data="pools" border stripe v-loading="loading">
        <el-table-column prop="period" label="期次" width="100" />
        <el-table-column label="池总额" width="140" align="right">
          <template #default="{ row }">{{ fmt(row.poolAmt) }}</template>
        </el-table-column>
        <el-table-column label="分摊规则" width="140">
          <template #default="{ row }">
            <el-tag size="small">{{ ruleName(row.rule) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" />
        <el-table-column prop="createBy" label="录入人" width="110" />
        <el-table-column prop="updateDate" label="更新时间" width="170" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" :disabled="!canWrite" @click="openForm(row)">修改</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!pools.length && !loading" class="empty">尚无费用池——请按月录入当期制造费用总额</div>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <div class="head"><span class="title">各工单制造费用分摊结果</span>
        <el-button size="small" @click="loadSummaries">刷新</el-button></div>
      <el-table :data="summaries" border stripe size="small" max-height="360">
        <el-table-column prop="moNo" label="工单号" width="140" />
        <el-table-column prop="period" label="期次" width="80" />
        <el-table-column label="制造费用" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.overheadCost) }}</template>
        </el-table-column>
        <el-table-column label="总成本" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.totalCost) }}</template>
        </el-table-column>
        <el-table-column prop="mark" label="提示" min-width="180" />
      </el-table>
    </el-card>

    <el-dialog v-model="formVisible" :title="form.id ? '修改费用池' : '录入费用池'" width="460px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="期次" required>
          <el-date-picker v-model="form.period" type="month" placeholder="yyyyMM"
            value-format="YYYYMM" :disabled="!!form.id" style="width: 100%" />
        </el-form-item>
        <el-form-item label="池总额" required>
          <el-input-number v-model="form.poolAmt" :min="0" :precision="2"
            :disabled="!canWrite" style="width: 100%" />
        </el-form-item>
        <el-form-item label="分摊规则">
          <el-select v-model="form.rule" style="width: 100%">
            <el-option label="工时比例（默认）" value="TIME" />
            <el-option label="机器工时比例" value="MACHINE" />
            <el-option label="产量比例" value="QTY" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!canWrite" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { listPoolsApi, savePoolApi, listCostApi } from '@/api/mrp/cost'

const userStore = useUserStore()
const pools = ref([])
const summaries = ref([])
const loading = ref(false)
const formVisible = ref(false)
const form = reactive({ id: null, period: '', poolAmt: 0, rule: 'TIME', remark: '' })

const roles = computed(() => userStore.userInfo?.roles || [])
const canWrite = computed(() =>
  roles.value.includes('ROLE_COST_ACCOUNTANT') || roles.value.includes('ROLE_ADMIN'))

function fmt(v) {
  if (v === null || v === undefined) return '—'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function ruleName(r) {
  return { TIME: '工时比例', MACHINE: '机器工时比例', QTY: '产量比例' }[r] || r || '工时比例'
}

async function load() {
  loading.value = true
  try {
    pools.value = (await listPoolsApi()).data || []
  } finally {
    loading.value = false
  }
}

async function loadSummaries() {
  const res = await listCostApi({})
  summaries.value = (res.data || []).map(r => r.summary).filter(Boolean)
}

function openForm(row) {
  Object.assign(form, row
    ? { id: row.id, period: row.period, poolAmt: Number(row.poolAmt), rule: row.rule || 'TIME', remark: row.remark || '' }
    : { id: null, period: '', poolAmt: 0, rule: 'TIME', remark: '' })
  formVisible.value = true
}

async function save() {
  if (!form.period) {
    ElMessage.warning('请选择期次')
    return
  }
  try {
    await savePoolApi({ period: form.period, poolAmt: form.poolAmt, rule: form.rule, remark: form.remark })
    ElMessage.success('费用池已保存（期次唯一 upsert）')
    formVisible.value = false
    await Promise.all([load(), loadSummaries()])
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  }
}

onMounted(() => {
  load()
  loadSummaries()
})
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.empty { text-align: center; color: #909399; padding: 24px 0; }
</style>
