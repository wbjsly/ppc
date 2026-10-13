<template>
  <div>
    <el-page-header content="外协发料（5.10.3）— 甲供外协派工单从仓库调拨原材料至供应商保管：所有权留厂内，出库入外协在途，收货定额冲减、余料退回"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-input v-model="filters.dispatchNo" size="small" clearable placeholder="派工单号"
                style="width: 170px" @keyup.enter="load" />
      <el-input v-model="filters.supplierCode" size="small" clearable placeholder="供应商编码"
                style="width: 160px" @keyup.enter="load" />
      <el-select v-model="filters.status" size="small" clearable placeholder="状态" style="width: 140px">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已出库" value="POSTED" />
        <el-option label="余料已退" value="RETURNED" />
      </el-select>
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-button size="small" type="success" @click="openCreate">新建发料单</el-button>
      <span class="tip">仅甲供（COMPANY_SUPPLY）外协派工单可发料；出库即入外协在途，收货按 BOM 定额自动冲减</span>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small" max-height="560">
      <el-table-column prop="osmNo" label="发料单号" width="170" />
      <el-table-column prop="dispatchNo" label="派工单号" width="150" show-overflow-tooltip />
      <el-table-column prop="moNo" label="工单号" width="130" show-overflow-tooltip />
      <el-table-column label="工序" width="70" align="center">
        <template #default="{ row }">{{ row.opSeq ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="供应商" width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.supplierCode }}（{{ row.supplierName }}）</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="stTag(row.status)" size="small">{{ stText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="待退回余量" width="110" align="right">
        <template #default="{ row }">
          <span v-if="row.remainQty > 0" style="color: #e6a23c; font-weight: 600">
            {{ row.remainQty }}
          </span>
          <span v-else style="color: #909399">0</span>
        </template>
      </el-table-column>
      <el-table-column prop="postDate" label="出库时间" width="150" :formatter="dtCol" />
      <el-table-column prop="returnDate" label="退回时间" width="150" :formatter="dtCol" />
      <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="200" align="center" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'DRAFT'" size="small" type="success" link
                     @click="doPost(row)">出库</el-button>
          <el-button v-if="row.status === 'POSTED' && row.remainQty > 0" size="small"
                     type="warning" link @click="openReturn(row)">退回</el-button>
          <el-button v-if="row.status === 'DRAFT'" size="small" type="danger" link
                     @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无发料单（先在 5.10.1 把派工单供料方式设为甲供）"
              :image-size="60" />

    <!-- 新建弹窗 -->
    <el-dialog v-model="createVisible" title="新建外协发料单" width="920px" :close-on-click-modal="false">
      <el-form label-width="90px" size="small">
        <el-form-item label="外协派工单">
          <el-select v-model="form.dispatchId" filterable placeholder="选择甲供外协派工单"
                     style="width: 100%" @change="onDispatchChange">
            <el-option v-for="d in dispatchOptions" :key="d.id"
                       :label="`${d.dispatchNo}｜${d.moNo || ''} 工序 ${d.opSeq ?? '-'}｜${d.supplierName || d.supplierCode || '未维护供应商'}`"
                       :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="选填" />
        </el-form-item>
      </el-form>

      <el-table :data="form.lines" border size="small">
        <el-table-column label="物料" min-width="220">
          <template #default="{ row }">
            <el-select v-model="row.itemCode" filterable size="small" style="width: 100%"
                       @change="(v) => onItemChange(row, v)">
              <el-option v-for="it in itemOptions" :key="it.code"
                         :label="`${it.name}（${it.code}）`" :value="it.code" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="批次" width="140">
          <template #default="{ row }">
            <el-input v-model="row.batchNo" size="small" placeholder="无批留空" />
          </template>
        </el-table-column>
        <el-table-column label="仓库" width="170">
          <template #default="{ row }">
            <el-select v-model="row.warehouseCode" size="small" style="width: 100%">
              <el-option v-for="w in warehouses" :key="w.whCode"
                         :label="`${w.whName}（${w.whCode}）`" :value="w.whCode" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="单位" width="80">
          <template #default="{ row }">
            <el-input v-model="row.unit" size="small" placeholder="PCS" />
          </template>
        </el-table-column>
        <el-table-column label="请领数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.reqQty" :min="0" :precision="4" size="small"
                             controls-position="right" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ $index }">
            <el-button size="small" type="danger" link
                       :disabled="form.lines.length <= 1"
                       @click="form.lines.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 8px">
        <el-button size="small" @click="form.lines.push(blankLine())">+ 加行</el-button>
        <span class="tip">创建即校验可用库存；不足将逐行列出行级缺口，不合格不落单</span>
      </div>

      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="doCreate">创建草稿</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="发料单详情" width="920px">
      <el-descriptions :column="3" border size="small" style="margin-bottom: 10px">
        <el-descriptions-item label="发料单号">{{ detail.osm?.osmNo }}</el-descriptions-item>
        <el-descriptions-item label="派工单号">{{ detail.osm?.dispatchNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="stTag(detail.osm?.status)" size="small">{{ stText(detail.osm?.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="工单/工序">{{ detail.osm?.moNo }} / {{ detail.osm?.opSeq ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="供应商" :span="2">{{ detail.osm?.supplierCode }}（{{ detail.osm?.supplierName }}）</el-descriptions-item>
        <el-descriptions-item label="出库时间">{{ detail.osm?.postDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="退回时间">{{ detail.osm?.returnDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detail.osm?.remark || '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-table :data="detail.lines || []" border size="small">
        <el-table-column prop="lineNo" label="#" width="46" align="center" />
        <el-table-column prop="itemCode" label="物料" width="130" show-overflow-tooltip />
        <el-table-column prop="itemName" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column prop="batchNo" label="批次" width="110" show-overflow-tooltip />
        <el-table-column prop="warehouseCode" label="仓库" width="100" />
        <el-table-column label="请领" width="80" align="right">
          <template #default="{ row }">{{ row.reqQty }}</template>
        </el-table-column>
        <el-table-column label="实发" width="80" align="right">
          <template #default="{ row }">{{ row.issuedQty }}</template>
        </el-table-column>
        <el-table-column label="已冲减" width="80" align="right">
          <template #default="{ row }">{{ row.consumedQty }}</template>
        </el-table-column>
        <el-table-column label="已退回" width="80" align="right">
          <template #default="{ row }">{{ row.returnedQty }}</template>
        </el-table-column>
        <el-table-column label="可退余量" width="90" align="right">
          <template #default="{ row }">
            <span :style="{ color: remainOf(row) > 0 ? '#e6a23c' : '#909399' }">{{ remainOf(row) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="tip" style="margin-top: 8px">
        收货过账按 BOM 快照定额自动冲减（实发 − 冲减 − 退回 = 可退余量）；余料退回走「退回」操作
      </div>
      <template #footer>
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 余料退回弹窗 -->
    <el-dialog v-model="returnVisible" title="余料退回（在途 → 工厂可用）" width="760px"
               :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" style="margin-bottom: 10px"
                title="按行填写退回数量（≤ 可退余量）；全部行退净后发料单置「余料已退」" />
      <el-table :data="returnLines" border size="small">
        <el-table-column prop="itemCode" label="物料" width="130" show-overflow-tooltip />
        <el-table-column prop="batchNo" label="批次" width="110" show-overflow-tooltip />
        <el-table-column prop="warehouseCode" label="仓库" width="100" />
        <el-table-column label="可退余量" width="90" align="right">
          <template #default="{ row }">{{ remainOf(row) }}</template>
        </el-table-column>
        <el-table-column label="本次退回" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.returnQty" :min="0" :max="remainOf(row)" :precision="4"
                             controls-position="right" size="small" style="width: 100%" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button size="small" @click="returnVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="doReturn">确认退回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createOsMaterialApi, deleteOsMaterialApi, getOsMaterialDetailApi,
  getOsMaterialListApi, getWorkbenchApi, postOsMaterialApi, returnOsMaterialApi
} from '@/api/mrp/outsourcing'
import { getEnabledWarehousesApi } from '@/api/inv/warehouse'
import { getItemOptionsApi } from '@/api/mdm/item'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const filters = reactive({ dispatchNo: '', supplierCode: '', status: '' })

const createVisible = ref(false)
const detailVisible = ref(false)
const returnVisible = ref(false)

const dispatchOptions = ref([])
const itemOptions = ref([])
const warehouses = ref([])

const form = reactive({ dispatchId: '', remark: '', lines: [] })
const detail = reactive({ osm: {}, lines: [] })
const returnOsmId = ref('')
const returnLines = ref([])

const stText = (s) => ({ DRAFT: '草稿', POSTED: '已出库', RETURNED: '余料已退' }[s] || s)
const stTag = (s) => ({ DRAFT: 'info', POSTED: 'warning', RETURNED: 'success' }[s] || 'info')
const dtCol = (r, c, v) => v || '-'
const remainOf = (l) => {
  const n = (x) => Number(x || 0)
  return n(l.issuedQty) - n(l.consumedQty) - n(l.returnedQty)
}
const blankLine = () => ({ itemCode: '', itemName: '', batchNo: '', warehouseCode: '', unit: 'PCS', reqQty: 1 })

async function load() {
  loading.value = true
  try {
    const params = {}
    if (filters.dispatchNo) params.dispatchNo = filters.dispatchNo
    if (filters.supplierCode) params.supplierCode = filters.supplierCode
    if (filters.status) params.status = filters.status
    const res = await getOsMaterialListApi(params)
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  form.dispatchId = ''
  form.remark = ''
  form.lines = [blankLine()]
  createVisible.value = true
  if (!dispatchOptions.value.length || !itemOptions.value.length || !warehouses.value.length) {
    const [wb, it, wh] = await Promise.all([
      getWorkbenchApi({}), getItemOptionsApi(), getEnabledWarehousesApi()
    ])
    // 仅甲供派工单可发料（自购在服务端 422，这里前置过滤减少误选）
    dispatchOptions.value = (wb.data || []).filter((d) => d.osSupplyType === 'COMPANY_SUPPLY')
    itemOptions.value = it.data || []
    warehouses.value = wh.data || []
  }
}

function onDispatchChange() {
  // 派工单切换不重置已填行（物料/仓库通用），仅清备注
}

function onItemChange(row, code) {
  const it = itemOptions.value.find((i) => i.code === code)
  row.itemName = it ? it.name : ''
}

async function doCreate() {
  if (!form.dispatchId) {
    ElMessage.warning('请选择外协派工单')
    return
  }
  const lines = form.lines
      .filter((l) => l.itemCode && l.warehouseCode && Number(l.reqQty) > 0)
      .map((l) => ({
        itemCode: l.itemCode, itemName: l.itemName, batchNo: l.batchNo || '',
        warehouseCode: l.warehouseCode, unit: l.unit || 'PCS', reqQty: l.reqQty
      }))
  if (!lines.length) {
    ElMessage.warning('请至少填写一行完整明细（物料/仓库/数量）')
    return
  }
  saving.value = true
  try {
    await createOsMaterialApi({ dispatchId: form.dispatchId, remark: form.remark, lines })
    ElMessage.success('发料单已创建（草稿）')
    createVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  const res = await getOsMaterialDetailApi(row.id)
  detail.osm = res.data?.osm || {}
  detail.lines = res.data?.lines || []
  detailVisible.value = true
}

async function doPost(row) {
  try {
    await ElMessageBox.confirm(
        `确认对 ${row.osmNo} 执行出库过账？将预扣可用库存并转入外协在途。`,
        '出库过账', { type: 'warning' })
  } catch { return }
  saving.value = true
  try {
    await postOsMaterialApi(row.id)
    ElMessage.success('出库过账完成')
    await load()
  } finally {
    saving.value = false
  }
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除草稿 ${row.osmNo}？`, '删除', { type: 'warning' })
  } catch { return }
  await deleteOsMaterialApi(row.id)
  ElMessage.success('已删除')
  await load()
}

async function openReturn(row) {
  const res = await getOsMaterialDetailApi(row.id)
  const lines = (res.data?.lines || []).filter((l) => remainOf(l) > 0)
  if (!lines.length) {
    ElMessage.info('该发料单已无可退余量')
    return
  }
  returnOsmId.value = row.id
  returnLines.value = lines.map((l) => ({ ...l, returnQty: remainOf(l) }))
  returnVisible.value = true
}

async function doReturn() {
  const rows_ = returnLines.value
      .filter((l) => Number(l.returnQty) > 0)
      .map((l) => ({ lineId: l.id, qty: l.returnQty }))
  if (!rows_.length) {
    ElMessage.warning('请填写至少一行退回数量')
    return
  }
  saving.value = true
  try {
    await returnOsMaterialApi(returnOsmId.value, rows_)
    ElMessage.success('余料退回完成')
    returnVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.tip {
  color: #909399;
  font-size: 12px;
}
</style>
