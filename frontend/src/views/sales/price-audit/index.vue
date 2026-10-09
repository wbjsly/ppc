<template>
  <div class="pa-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="取价记录（3.6.4）"
      description="价格审计明细（C-4.3-04 / BR-4.3-35/36）：原始基准价、每层折扣率、叠加规则、最终生效价、冲突裁决、操作人与时间戳，只增不改，保留期按 AUDIT_RETENTION_DAYS 参数。"
    />

    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">价格审计日志</span>
          <div class="header-actions">
            <el-select v-model="query.srcType" placeholder="来源" clearable style="width: 130px;" @change="load(1)">
              <el-option label="销售订单行" value="SO" />
              <el-option label="报价行" value="QUOTE" />
            </el-select>
            <el-input v-model="query.srcId" placeholder="单据 ID" clearable style="width: 170px;" @keyup.enter="load(1)" />
            <el-input v-model="query.lineNo" placeholder="行号" clearable style="width: 80px;" @keyup.enter="load(1)" />
            <el-input v-model="query.operatorId" placeholder="操作人" clearable style="width: 130px;" @keyup.enter="load(1)" />
            <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                            start-placeholder="开始" end-placeholder="结束" style="width: 240px;" />
            <el-button type="primary" @click="load(1)">查询</el-button>
            <el-button :icon="Refresh" @click="load()">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.operateAt) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.srcType === 'SO' ? 'primary' : 'warning'">
              {{ row.srcType === 'SO' ? 'SO 行' : row.srcType === 'QUOTE' ? '报价行' : '试算' }}
            </el-tag>
            <span class="mono"> #{{ row.lineNo == null ? '-' : row.lineNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="customerCode" label="客户" width="110" show-overflow-tooltip />
        <el-table-column prop="itemCode" label="SKU" width="140" />
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column label="基准价" width="96" align="right">
          <template #default="{ row }">{{ row.basePrice }}</template>
        </el-table-column>
        <el-table-column label="各层折扣" width="170">
          <template #default="{ row }">
            <span class="rates">
              渠 {{ pct(row.channelRate) }} / 阶 {{ pct(row.ladderRate) }} / 促 {{ pct(row.promoRate) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="规则" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.stackMode === 'STACKED' ? 'warning' : 'info'">
              {{ row.stackMode === 'STACKED' ? '逐层' : '单层' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最终价" width="100" align="right">
          <template #default="{ row }"><b>{{ row.finalPrice }}</b></template>
        </el-table-column>
        <el-table-column label="采用" width="86">
          <template #default="{ row }">{{ srcName(row.appliedSource) }}</template>
        </el-table-column>
        <el-table-column label="特批单" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.specialApprovalNo" type="warning" size="small">{{ row.specialApprovalNo }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="operatorId" label="操作人" width="120" show-overflow-tooltip />
        <el-table-column label="保留至" width="110">
          <template #default="{ row }">{{ row.retentionUntil || '-' }}</template>
        </el-table-column>
        <el-table-column label="详情" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end;"
        v-model:current-page="query.current" :page-size="query.size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </el-card>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="取价过程明细" width="640px">
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="SKU">{{ detail.itemCode }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ detail.qty }}</el-descriptions-item>
        <el-descriptions-item label="原始基准价">{{ detail.basePrice }}</el-descriptions-item>
        <el-descriptions-item label="最终生效价"><b>{{ detail.finalPrice }}</b></el-descriptions-item>
        <el-descriptions-item label="渠道层">{{ pct(detail.channelRate) }}</el-descriptions-item>
        <el-descriptions-item label="阶梯层">{{ pct(detail.ladderRate) }}</el-descriptions-item>
        <el-descriptions-item label="促销层">{{ pct(detail.promoRate) }}</el-descriptions-item>
        <el-descriptions-item label="叠加规则">{{ detail.stackMode }}</el-descriptions-item>
        <el-descriptions-item label="采用来源">{{ srcName(detail.appliedSource) }}</el-descriptions-item>
        <el-descriptions-item label="特批单">{{ detail.specialApprovalNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="操作人 / 时间" :span="2">
          {{ detail.operatorId }} @ {{ fmtTime(detail.operateAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="冲突裁决" :span="2">
          <pre class="json">{{ fmtJson(detail.conflictJson) }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="未采用来源" :span="2">
          <pre class="json">{{ fmtJson(detail.rejectedJson) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getAuditApi } from '@/api/sd/pricing'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const range = ref([])
const query = reactive({ current: 1, size: 20, srcType: '', srcId: '', lineNo: '', operatorId: '' })

const detailVisible = ref(false)
const detail = reactive({})

onMounted(() => load(1))

async function load(page) {
  if (page) query.current = page
  loading.value = true
  try {
    const params = {
      current: query.current, size: query.size,
      srcType: query.srcType || '', srcId: query.srcId || '',
      operatorId: query.operatorId || ''
    }
    if (query.lineNo) params.lineNo = query.lineNo
    if (range.value && range.value.length === 2) {
      params.from = range.value[0]
      params.to = range.value[1]
    }
    const res = await getAuditApi(params)
    rows.value = res.data.records || []
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function showDetail(row) {
  Object.assign(detail, row)
  detailVisible.value = true
}

function fmtJson(j) {
  if (!j) return '（无）'
  try {
    return JSON.stringify(JSON.parse(j), null, 2)
  } catch (e) {
    return String(j)
  }
}
function pct(v) { return v == null ? '-' : (Number(v) * 100).toFixed(2) + '%' }
function srcName(s) {
  return { CHANNEL: '渠道', LADDER: '阶梯', PROMO: '促销', STACKED: '叠加', PROTOCOL: '协议', NONE: '基准价' }[s] || s || '-'
}
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '-' }
</script>

<style scoped>
.pa-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.rates { font-size: 12px; color: #606266; }
.mono { color: #909399; font-size: 12px; }
.json { font-size: 12px; background: #f5f7fa; padding: 6px; margin: 0; border-radius: 4px; }
</style>
