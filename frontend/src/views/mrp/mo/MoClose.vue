<template>
  <div>
    <el-page-header content="工单关闭（5.4.5）— 手动完工确认（Released→Completed）→ 关闭预检 → 关闭（终态，锁定数据）"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-select v-model="status" style="width: 170px" size="small" @change="load">
        <el-option label="已释放（可完工）" value="RELEASED" />
        <el-option label="已完工（可关闭）" value="COMPLETED" />
        <el-option label="已关闭" value="CLOSED" />
        <el-option label="全部状态" value="" />
      </el-select>
      <el-button size="small" @click="load">刷新</el-button>
      <span class="tip">过渡：完工确认为手动按钮，5.7 报工落地后改为自动；关闭前置的工序/成本/在制校验为占位钩子（proposal D3）</span>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small">
      <el-table-column prop="moNo" label="工单号" width="160" />
      <el-table-column prop="productCode" label="产品" width="130" />
      <el-table-column prop="productName" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="qty" label="数量" width="80" />
      <el-table-column prop="qualifiedQty" label="合格产出" width="90">
        <template #default="{ row }">{{ row.qualifiedQty ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="完工留痕" width="160">
        <template #default="{ row }">{{ row.completeBy || '—' }} {{ (row.completeAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="关闭留痕" width="160">
        <template #default="{ row }">{{ row.closeBy || '—' }} {{ (row.closeAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'RELEASED'" link type="primary" size="small"
                     @click="openComplete(row)">完工确认</el-button>
          <el-button v-if="row.status === 'COMPLETED'" link type="warning" size="small"
                     @click="openPrecheck(row)">关闭预检</el-button>
          <el-button v-if="row.status === 'COMPLETED'" link type="danger" size="small"
                     @click="doClose(row)">关闭</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无工单" :image-size="60" />

    <!-- 完工确认 -->
    <el-dialog v-model="completeVisible" :title="`完工确认 - ${currentMo?.moNo || ''}`" width="460px">
      <el-form label-width="96px">
        <el-form-item label="计划数量">{{ currentMo?.qty }}</el-form-item>
        <el-form-item label="合格产出" required>
          <el-input-number v-model="qualifiedQty" :min="0" :precision="4" />
          <div class="tip">最后一道工序的工序级确认由 5.7 报工推进（本期手动过渡）</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="completeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doComplete">确认完工</el-button>
      </template>
    </el-dialog>

    <!-- 关闭预检 -->
    <el-dialog v-model="precheckVisible" :title="`关闭预检 - ${currentMo?.moNo || ''}`" width="560px">
      <el-alert v-if="precheck" :type="precheck.passed ? 'success' : 'error'"
                :title="precheck.passed ? '全部校验通过，可关闭' : '存在未通过校验，关闭将被阻断'"
                :closable="false" style="margin-bottom: 10px" />
      <el-table :data="precheck?.checks || []" size="small" border>
        <el-table-column prop="name" label="校验项" min-width="240" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.passed ? 'success' : 'danger'" size="small">{{ row.passed ? '通过' : '不通过' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="200" />
      </el-table>
      <template #footer>
        <el-button @click="precheckVisible = false">关闭</el-button>
        <el-button type="danger" :disabled="!precheck?.passed" :loading="saving"
                   @click="doClose(currentMo)">执行关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMosApi, completeMoApi, closePrecheckApi, closeMoApi } from '@/api/mrp/mo'

const statusText = s => ({
  PLANNED: '计划', PENDING: '待审核', CONFIRMED: '已确认', RELEASED: '已释放',
  HOLD: '挂起', COMPLETED: '已完工', CLOSED: '已关闭', CANCELLED: '已取消'
}[s] || s)
const statusTag = s => ({
  PLANNED: 'info', PENDING: 'warning', CONFIRMED: '', RELEASED: 'success',
  HOLD: 'warning', COMPLETED: 'success', CLOSED: 'info', CANCELLED: 'danger'
}[s] || 'info')

const status = ref('RELEASED')
const rows = ref([])
const loading = ref(false)
const saving = ref(false)

const currentMo = ref(null)
const completeVisible = ref(false)
const qualifiedQty = ref(0)
const precheckVisible = ref(false)
const precheck = ref(null)

async function load() {
  loading.value = true
  try {
    const res = await getMosApi({ status: status.value || undefined })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openComplete(row) {
  currentMo.value = row
  qualifiedQty.value = Number(row.qty)
  completeVisible.value = true
}

async function doComplete() {
  saving.value = true
  try {
    await completeMoApi(currentMo.value.id, qualifiedQty.value)
    ElMessage.success('完工确认成功（状态 → 已完工）')
    completeVisible.value = false
    load()
  } catch (e) {
    console.warn('[mo-close] complete failed', e)
  } finally {
    saving.value = false
  }
}

async function openPrecheck(row) {
  currentMo.value = row
  const res = await closePrecheckApi(row.id)
  precheck.value = res.data
  precheckVisible.value = true
}

async function doClose(row) {
  await ElMessageBox.confirm(
    `关闭工单 ${row.moNo}？关闭为终态，将锁定工单数据（成本锁定口径）`, '工单关闭', { type: 'warning' })
  saving.value = true
  try {
    await closeMoApi(row.id)
    ElMessage.success('工单已关闭（终态）')
    precheckVisible.value = false
    load()
  } catch (e) {
    console.warn('[mo-close] close failed', e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.tip { color: #909399; font-size: 12px; }
</style>
