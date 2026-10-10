<template>
  <div>
    <el-page-header content="工单变更（5.4.4）— 挂起/恢复/取消（原因必填）+ 拆分（子单合计=剩余量，C-4.5-15）；合并为只读校验提示"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-select v-model="status" style="width: 160px" size="small" @change="load">
        <el-option label="已释放" value="RELEASED" />
        <el-option label="挂起" value="HOLD" />
        <el-option label="全部状态" value="" />
      </el-select>
      <el-input v-model="productCode" clearable placeholder="产品编码" size="small" style="width: 170px"
                @keyup.enter="load" />
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-tooltip content="合并约束（BR-4.5-06，只读提示）：同产品、同 BOM 版本、同工艺路线的已释放未开工工单方可合并；第一期不执行合并动作（proposal D6）" placement="bottom">
        <el-button size="small" text type="info">合并约束说明</el-button>
      </el-tooltip>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small">
      <el-table-column prop="moNo" label="工单号" width="160" />
      <el-table-column prop="productCode" label="产品" width="130" />
      <el-table-column prop="productName" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="qty" label="剩余数量" width="90" />
      <el-table-column prop="bomVersion" label="BOM" width="70" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="拆分来源" width="150">
        <template #default="{ row }">{{ row.splitFromMo || '—' }}</template>
      </el-table-column>
      <el-table-column prop="holdReason" label="挂起/取消原因" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ row.holdReason || row.cancelReason || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'RELEASED'">
            <el-button link type="warning" size="small" @click="openHold(row)">挂起</el-button>
            <el-button link type="danger" size="small" @click="openCancel(row)">取消</el-button>
            <el-button link type="primary" size="small" @click="openSplit(row)">拆分</el-button>
          </template>
          <el-button v-if="row.status === 'HOLD'" link type="success" size="small"
                     @click="doResume(row)">恢复</el-button>          <el-button v-if="row.status === 'HOLD'" link type="danger" size="small"
                     @click="openCancel(row)">取消</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无工单" :image-size="60" />

    <!-- 挂起 -->
    <el-dialog v-model="holdVisible" :title="`挂起 - ${currentMo?.moNo || ''}`" width="460px">
      <el-input v-model="holdReason" type="textarea" :rows="3" maxlength="500"
                placeholder="挂起原因（必填，如：设备检修 / 物料待料）" />
      <template #footer>
        <el-button @click="holdVisible = false">取消</el-button>
        <el-button type="warning" :loading="saving" @click="doHold">确认挂起</el-button>
      </template>
    </el-dialog>

    <!-- 取消 -->
    <el-dialog v-model="cancelVisible" :title="`取消工单 - ${currentMo?.moNo || ''}`" width="460px">
      <el-alert type="error" :closable="false" title="取消为终态操作，不可恢复" style="margin-bottom: 8px" />
      <el-input v-model="cancelReason" type="textarea" :rows="3" maxlength="500"
                placeholder="取消原因（必填）" />
      <template #footer>
        <el-button @click="cancelVisible = false">返回</el-button>
        <el-button type="danger" :loading="saving" @click="doCancel">确认取消</el-button>
      </template>
    </el-dialog>

    <!-- 拆分 -->
    <el-dialog v-model="splitVisible" :title="`拆分 - ${currentMo?.moNo || ''}（剩余 ${currentMo?.qty}）`" width="560px">
      <el-alert type="info" :closable="false" style="margin-bottom: 8px"
                title="子单数量合计必须等于原单剩余数量（C-4.5-15 L1）；子单继承 BOM 快照与交期，状态直接为已释放" />
      <div v-for="(q, i) in splitQtys" :key="i" class="split-row">
        <span>子单 {{ i + 1 }}</span>
        <el-input-number v-model="splitQtys[i]" :min="0.0001" :precision="4" size="small" />
        <el-button link type="danger" size="small" @click="splitQtys.splice(i, 1)"
                   :disabled="splitQtys.length <= 1">删除</el-button>
      </div>
      <div class="split-row">
        <el-button size="small" @click="splitQtys.push(1)">+ 加一张子单</el-button>
        <span :style="{ color: sumOk ? '#67c23a' : '#f56c6c' }">
          合计 {{ splitSum }} / 剩余 {{ currentMo?.qty }} {{ sumOk ? '✓' : '✗' }}
        </span>
      </div>
      <el-form-item label="变更原因" label-width="80px" style="margin-top: 8px">
        <el-input v-model="splitReason" type="textarea" :rows="2" placeholder="拆分原因（留痕到原单与子单）" />
      </el-form-item>
      <template #footer>
        <el-button @click="splitVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!sumOk" @click="doSplit">确认拆分</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMosApi, holdMoApi, resumeMoApi, cancelMoApi, splitMoApi } from '@/api/mrp/mo'

const statusText = s => ({
  PLANNED: '计划', PENDING: '待审核', CONFIRMED: '已确认', RELEASED: '已释放',
  HOLD: '挂起', COMPLETED: '已完工', CLOSED: '已关闭', CANCELLED: '已取消'
}[s] || s)
const statusTag = s => ({
  PLANNED: 'info', PENDING: 'warning', CONFIRMED: '', RELEASED: 'success',
  HOLD: 'warning', COMPLETED: 'success', CLOSED: 'info', CANCELLED: 'danger'
}[s] || 'info')

const status = ref('RELEASED')
const productCode = ref('')
const rows = ref([])
const loading = ref(false)
const saving = ref(false)

const currentMo = ref(null)
const holdVisible = ref(false)
const holdReason = ref('')
const cancelVisible = ref(false)
const cancelReason = ref('')
const splitVisible = ref(false)
const splitQtys = ref([])
const splitReason = ref('')

const splitSum = computed(() => splitQtys.value.reduce((s, q) => s + (q || 0), 0))
const sumOk = computed(() =>
  currentMo.value && Math.abs(splitSum.value - Number(currentMo.value.qty)) < 1e-6)

async function load() {
  loading.value = true
  try {
    const res = await getMosApi({
      status: status.value || undefined,
      productCode: productCode.value || undefined
    })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openHold(row) {
  currentMo.value = row
  holdReason.value = ''
  holdVisible.value = true
}

async function doHold() {
  if (!holdReason.value.trim()) { ElMessage.warning('挂起原因必填'); return }
  saving.value = true
  try {
    await holdMoApi(currentMo.value.id, holdReason.value.trim())
    ElMessage.success('已挂起')
    holdVisible.value = false
    load()
  } catch (e) {
    console.warn('[mo-change] hold failed', e)
  } finally {
    saving.value = false
  }
}

async function doResume(row) {
  try {
    await resumeMoApi(row.id)
    ElMessage.success('已恢复为已释放')
    load()
  } catch (e) {
    console.warn('[mo-change] resume failed', e)
  }
}

function openCancel(row) {
  currentMo.value = row
  cancelReason.value = ''
  cancelVisible.value = true
}

async function doCancel() {
  if (!cancelReason.value.trim()) { ElMessage.warning('取消原因必填'); return }
  saving.value = true
  try {
    await cancelMoApi(currentMo.value.id, cancelReason.value.trim())
    ElMessage.success('已取消（终态）')
    cancelVisible.value = false
    load()
  } catch (e) {
    console.warn('[mo-change] cancel failed', e)
  } finally {
    saving.value = false
  }
}

function openSplit(row) {
  currentMo.value = row
  splitQtys.value = [Number(row.qty)]
  splitReason.value = ''
  splitVisible.value = true
}

async function doSplit() {
  await ElMessageBox.confirm(
    `拆分为 ${splitQtys.value.length} 张子单（合计 ${splitSum.value}）？原单将减至剩余 ${Number(currentMo.value.qty) - splitSum.value}`,
    '工单拆分', { type: 'warning' })
  saving.value = true
  try {
    await splitMoApi(currentMo.value.id, splitQtys.value, splitReason.value.trim())
    ElMessage.success('拆分成功（子单已继承快照与交期）')
    splitVisible.value = false
    load()
  } catch (e) {
    console.warn('[mo-change] split failed', e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.split-row { display: flex; gap: 10px; align-items: center; margin-bottom: 6px; font-size: 13px; }
</style>
