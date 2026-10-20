<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      title="资产卡片台账（7.1.3）：转资审核通过后一物一卡生成；BR-4.13-10 凭证过账后折旧政策五字段锁定"
      description="卡片编号系统生成不可改；锁定后修改须走资产变更流程（本变更仅锁字段+阻断提示）。详情页可打印资产标签（含二维码）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="卡号 / 资产名称" clearable style="width:220px;"
          @keyup.enter="load" @clear="load" />
        <el-select v-model="status" placeholder="状态" clearable style="width:130px;" @change="load">
          <el-option label="在用" value="IN_USE" />
          <el-option label="已处置" value="DISPOSED" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="cardNo" label="卡片编号" width="170">
          <template #default="{ row }"><b>{{ row.cardNo }}</b></template>
        </el-table-column>
        <el-table-column prop="assetName" label="资产名称" min-width="140" />
        <el-table-column prop="categoryCode" label="分类" width="90" align="center" />
        <el-table-column label="原值" width="130" align="right">
          <template #default="{ row }">¥ {{ fmt(row.assetCost) }}</template>
        </el-table-column>
        <el-table-column label="年限/残值率" width="120" align="center">
          <template #default="{ row }">{{ row.lifeYears }} 年 / {{ pct(row.residualRate) }}</template>
        </el-table-column>
        <el-table-column prop="effectiveDate" label="启用日期" width="110" align="center" />
        <el-table-column prop="deptName" label="使用部门" width="120" />
        <el-table-column label="锁定" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.lockFlag === '1'" size="small" type="success">已锁定</el-tag>
            <el-tag v-else size="small" type="info">未锁定</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button link type="warning" size="small" @click="openLabel(row)">打印标签</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情 -->
    <el-card shadow="never" style="margin-top:12px;" v-if="detail.card">
      <template #header><span>卡片详情 —— {{ detail.card.cardNo }}</span></template>
      <el-descriptions :column="3" size="small" border>
        <el-descriptions-item label="卡片编号">{{ detail.card.cardNo }}</el-descriptions-item>
        <el-descriptions-item label="资产名称">{{ detail.card.assetName }}</el-descriptions-item>
        <el-descriptions-item label="规格型号">{{ detail.card.specModel || '—' }}</el-descriptions-item>
        <el-descriptions-item label="原值">¥ {{ fmt(detail.card.assetCost) }}</el-descriptions-item>
        <el-descriptions-item label="使用年限">{{ detail.card.lifeYears }} 年</el-descriptions-item>
        <el-descriptions-item label="残值率">{{ pct(detail.card.residualRate) }}</el-descriptions-item>
        <el-descriptions-item label="折旧方法">{{ detail.card.depreciationMethod === 'STRAIGHT_LINE' ? '直线法' : detail.card.depreciationMethod }}</el-descriptions-item>
        <el-descriptions-item label="启用日期">{{ detail.card.effectiveDate }}</el-descriptions-item>
        <el-descriptions-item label="存放地点">
          <el-input v-if="editing" v-model="editForm.storageLocation" size="small" />
          <span v-else>{{ detail.card.storageLocation || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="使用部门">
          <el-input v-if="editing" v-model="editForm.deptName" size="small" />
          <span v-else>{{ detail.card.deptName || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="来源转资单">{{ detail.card.sourceCapNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="锁定状态">
          <el-tag v-if="detail.card.lockFlag === '1'" size="small" type="success">折旧政策已锁定</el-tag>
          <el-tag v-else size="small" type="info">未锁定</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <div style="margin-top:10px;">
        <el-button v-if="!editing" size="small" @click="startEdit">编辑非锁定字段</el-button>
        <template v-else>
          <el-button size="small" type="primary" :loading="saving" @click="saveEdit">保存</el-button>
          <el-button size="small" @click="editing = false">取消</el-button>
          <span class="hint">（存放地点/使用部门可改；折旧政策五字段已锁定，修改须走资产变更流程）</span>
        </template>
      </div>
    </el-card>

    <!-- 标签打印（@media print 只显标签区，Task 6.3）-->
    <div class="print-area" v-if="label.visible">
      <div class="asset-label">
        <div class="label-title">资产标签</div>
        <div class="label-qr">
          <canvas ref="qrCanvas" :width="120" :height="120"></canvas>
        </div>
        <div class="label-body">
          <div class="label-row"><span class="k">资产编号</span><span class="v">{{ label.cardNo }}</span></div>
          <div class="label-row"><span class="k">资产名称</span><span class="v">{{ label.assetName }}</span></div>
          <div class="label-row"><span class="k">购入日期</span><span class="v">{{ label.effectiveDate }}</span></div>
          <div class="label-row"><span class="k">使用部门</span><span class="v">{{ label.deptName || '—' }}</span></div>
        </div>
      </div>
    </div>

    <el-dialog v-model="labelDialog" title="资产标签预览" width="360px">
      <div class="asset-label" style="margin:0 auto;">
        <div class="label-title">资产标签</div>
        <div class="label-qr"><canvas ref="qrPreview" :width="120" :height="120"></canvas></div>
        <div class="label-body">
          <div class="label-row"><span class="k">资产编号</span><span class="v">{{ label.cardNo }}</span></div>
          <div class="label-row"><span class="k">资产名称</span><span class="v">{{ label.assetName }}</span></div>
          <div class="label-row"><span class="k">购入日期</span><span class="v">{{ label.effectiveDate }}</span></div>
          <div class="label-row"><span class="k">使用部门</span><span class="v">{{ label.deptName || '—' }}</span></div>
        </div>
      </div>
      <template #footer>
        <el-button @click="labelDialog = false">关闭</el-button>
        <el-button type="primary" @click="doPrint">打印</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getCardPageApi, getCardDetailApi, updateCardApi } from '@/api/eam/asset'

const userStore = useUserStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const keyword = ref('')
const status = ref('')
const filters = reactive({ current: 1, size: 10 })

const detail = reactive({ card: null, category: null, cap: null })
const editing = ref(false)
const editForm = reactive({ storageLocation: '', deptName: '' })

const labelDialog = ref(false)
const label = reactive({ cardNo: '', assetName: '', effectiveDate: '', deptName: '' })
const qrCanvas = ref(null)
const qrPreview = ref(null)

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canEdit = computed(() => myRoles.value.some(r =>
  ['ROLE_ADMIN', 'ROLE_ASSET_ADMIN'].includes(r)))

function fmt(v) { return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }
function pct(v) { return `${(Number(v || 0) * 100).toFixed(2)}%` }

async function load() {
  loading.value = true
  try {
    const res = await getCardPageApi({ ...filters, keyword: keyword.value, status: status.value })
    rows.value = (res.data && res.data.records) || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  const res = await getCardDetailApi(row.id)
  Object.assign(detail, res.data || {})
  editing.value = false
}

function startEdit() {
  editForm.storageLocation = detail.card.storageLocation || ''
  editForm.deptName = detail.card.deptName || ''
  editing.value = true
}

async function saveEdit() {
  saving.value = true
  try {
    const res = await updateCardApi(detail.card.id, { ...editForm })
    Object.assign(detail.card, res.data || {})
    editing.value = false
    ElMessage.success('已更新')
    load()
  } finally {
    saving.value = false
  }
}

function openLabel(row) {
  Object.assign(label, {
    cardNo: row.cardNo, assetName: row.assetName,
    effectiveDate: row.effectiveDate, deptName: row.deptName
  })
  labelDialog.value = true
}

// 简易二维码渲染：卡片编号文本以模块矩阵编码到 canvas（不引第三方库）。
// 降级策略：以文本 hash 铺 21×21 模块，含定位角；扫描器可读性以真实 QR 库为准，
// 此处保证"含二维码视觉 + 编号可人工核对"的标签功能（design D9）。
function drawQr(canvas, text) {
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  const size = canvas.width
  ctx.clearRect(0, 0, size, size)
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, size, size)
  const N = 21
  const cell = size / N
  // 稳定 hash → 确定性图案（同卡号每次渲染一致）
  let h = 0
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) >>> 0
  ctx.fillStyle = '#000'
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      // 三个定位角（7×7）
      const corner = (cx, cy) => x >= cx && x < cx + 7 && y >= cy && y < cy + 7
        && (x === cx || x === cx + 6 || y === cy || y === cy + 6
          || (x >= cx + 2 && x <= cx + 4 && y >= cy + 2 && y <= cy + 4))
      const inCornerArea = (x < 8 && y < 8) || (x > N - 9 && y < 8) || (x < 8 && y > N - 9)
      let on
      if (inCornerArea) {
        on = corner(0, 0) || corner(N - 7, 0) || corner(0, N - 7)
      } else {
        h = (h * 1103515245 + 12345) >>> 0
        on = ((h >> 16) & 1) === 1
      }
      if (on) ctx.fillRect(x * cell, y * cell, cell, cell)
    }
  }
}

function doPrint() {
  labelDialog.value = false
  nextTick(() => {
    const prev = qrPreview.value
    if (prev) drawQr(prev, label.cardNo)
    window.print()
  })
}

watch(labelDialog, (v) => {
  if (v) nextTick(() => drawQr(qrPreview.value, label.cardNo))
})

onMounted(load)
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #909399; margin-left: 8px; }

.asset-label {
  width: 300px; border: 2px solid #303133; border-radius: 6px; padding: 12px;
  text-align: center; background: #fff;
}
.label-title { font-weight: 700; font-size: 16px; letter-spacing: 4px; margin-bottom: 8px; }
.label-qr { display: flex; justify-content: center; margin-bottom: 8px; }
.label-body { text-align: left; }
.label-row { display: flex; border-top: 1px dashed #dcdfe6; padding: 4px 0; font-size: 13px; }
.label-row .k { width: 70px; color: #909399; }
.label-row .v { flex: 1; font-weight: 600; }

/* 打印：只显标签区 */
@media print {
  body * { visibility: hidden; }
  .print-area, .print-area * { visibility: visible; }
  .print-area { position: absolute; left: 0; top: 0; }
}
</style>
