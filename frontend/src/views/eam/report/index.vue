<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- ================= 7.8.1 变动报表 ================= -->
      <el-tab-pane label="变动报表" name="change">
        <el-form inline>
          <el-form-item label="期间">
            <el-date-picker v-model="changeRange" type="daterange" value-format="YYYY-MM-DD"
                            start-placeholder="开始" end-placeholder="结束" style="width:260px" />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="changeQuery.evtType" clearable placeholder="全部" style="width:160px">
              <el-option v-for="(label, v) in EVT_TYPES" :key="v" :label="label" :value="v" />
            </el-select>
          </el-form-item>
          <el-form-item label="成本中心">
            <el-input v-model="changeQuery.costCenter" clearable style="width:140px" />
          </el-form-item>
          <el-button type="primary" @click="loadChange">查询</el-button>
          <el-button @click="doExportChange">导出 CSV</el-button>
        </el-form>
        <el-descriptions :column="6" border size="small" style="margin-bottom:12px">
          <el-descriptions-item label="期初原值">{{ summary.openCost }}</el-descriptions-item>
          <el-descriptions-item label="本期增加">
            <span style="color:#67c23a">+{{ summary.increase }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="本期减少">
            <span style="color:#f56c6c">{{ summary.decrease }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="期末原值">{{ summary.closeCost }}</el-descriptions-item>
          <el-descriptions-item label="期末累计">{{ summary.closeAccum }}</el-descriptions-item>
          <el-descriptions-item label="勾对">
            <el-tag :type="summary.reconOk ? 'success' : 'warning'">
              {{ summary.reconOk ? '平衡' : '异常' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="summary.reconOk === false" :title="summary.reconNote" type="warning"
                  :closable="false" style="margin-bottom:10px" />
        <el-table :data="changeRows" border size="small">
          <el-table-column prop="EVT_DATE" label="变动日期" width="110" />
          <el-table-column prop="EVT_TYPE" label="类型" width="140">
            <template #default="{ row }">{{ EVT_TYPES[row.EVT_TYPE] || row.EVT_TYPE }}</template>
          </el-table-column>
          <el-table-column prop="CARD_NO" label="卡号" width="130" />
          <el-table-column prop="ASSET_NAME" label="资产名称" min-width="140" show-overflow-tooltip />
          <el-table-column prop="CATEGORY_CODE" label="分类" width="90" />
          <el-table-column prop="DEPT_NAME" label="部门" width="110" show-overflow-tooltip />
          <el-table-column prop="SOURCE_NO" label="来源单号" width="150" />
          <el-table-column prop="AMOUNT_DELTA" label="变动金额" width="110">
            <template #default="{ row }">
              <span :style="{ color: row.AMOUNT_DELTA > 0 ? '#67c23a' : (row.AMOUNT_DELTA < 0 ? '#f56c6c' : '#909399') }">
                {{ row.AMOUNT_DELTA }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="REMARK" label="说明" min-width="180" show-overflow-tooltip />
        </el-table>
        <el-pagination layout="total, prev, pager, next" :total="changeTotal"
                       :page-size="changeQuery.size" :current-page="changeQuery.current"
                       @current-change="onchangePage" style="margin-top:10px" />
      </el-tab-pane>

      <!-- ================= 7.8.2 折旧明细 ================= -->
      <el-tab-pane label="折旧明细" name="depreciation">
        <el-form inline>
          <el-form-item label="期间">
            <el-input v-model="depPeriod" placeholder="YYYYMM" style="width:120px" />
          </el-form-item>
          <el-button type="primary" @click="loadDep">查询</el-button>
          <el-button @click="doExportDep">导出明细 CSV</el-button>
          <el-button @click="doExportLedger">导出台账 CSV</el-button>
        </el-form>
        <el-descriptions :column="4" border size="small" style="margin-bottom:12px">
          <el-descriptions-item label="计提单">{{ depSummaryHead.runNo || '未计提' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ depSummaryHead.status || '—' }}</el-descriptions-item>
          <el-descriptions-item label="折旧总额">{{ depSummaryHead.totalDepr ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="勾稽差额 RECON_DIFF">
            <el-tag :type="Number(depSummaryHead.reconDiff || 0) === 0 ? 'success' : 'warning'">
              {{ depSummaryHead.reconDiff ?? '—' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <el-radio-group v-model="depView" style="margin-bottom:10px" @change="onViewChange">
          <el-radio-button value="category">分类汇总</el-radio-button>
          <el-radio-button value="costCenter">成本中心汇总</el-radio-button>
          <el-radio-button value="detail">当期明细</el-radio-button>
          <el-radio-button value="ledger">净值台账</el-radio-button>
        </el-radio-group>

        <template v-if="depView !== 'detail' && depView !== 'ledger'">
          <el-table :data="depSummaryRows" border size="small">
            <el-table-column prop="dim" label="维度" />
            <el-table-column prop="curAmt" label="本期折旧" width="130" />
            <el-table-column prop="prevAmt" label="上期折旧" width="130" />
            <el-table-column prop="diff" label="环比差异" width="120">
              <template #default="{ row }">
                <span :style="{ color: row.alerted ? '#f56c6c' : '#303133' }">{{ row.diff }}</span>
              </template>
            </el-table-column>
            <el-table-column label="预警" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.alerted" type="danger" size="small">超容差</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <template v-else-if="depView === 'detail'">
          <el-table :data="depDetailRows" border size="small">
            <el-table-column prop="CARD_NO" label="卡号" width="140" />
            <el-table-column prop="ASSET_NAME" label="资产名称" min-width="140" show-overflow-tooltip />
            <el-table-column prop="CATEGORY_CODE" label="分类" width="90" />
            <el-table-column prop="COST_CENTER" label="成本中心" width="110" />
            <el-table-column prop="BEGIN_NBV" label="期初净值" width="110" />
            <el-table-column prop="PERIOD_DEPR" label="本期折旧" width="110" />
            <el-table-column prop="END_ACCUM_DEPR" label="期末累计" width="110" />
            <el-table-column prop="END_NBV" label="期末净值" width="110" />
          </el-table>
        </template>

        <template v-else>
          <el-form inline>
            <el-form-item label="折旧状态">
              <el-select v-model="ledgerQuery.deprStatus" clearable style="width:180px">
                <el-option label="未计提" value="NOT_DEPRECIATED" />
                <el-option label="计提中" value="IN_PROGRESS" />
                <el-option label="已提足" value="FULLY_DEPRECIATED" />
              </el-select>
            </el-form-item>
            <el-button type="primary" @click="loadLedger">查询台账</el-button>
          </el-form>
          <el-table :data="ledgerRows" border size="small">
            <el-table-column prop="CARD_NO" label="卡号" width="140" />
            <el-table-column prop="ASSET_NAME" label="资产名称" min-width="140" show-overflow-tooltip />
            <el-table-column prop="CATEGORY_CODE" label="分类" width="90" />
            <el-table-column prop="DEPT_NAME" label="部门" width="110" show-overflow-tooltip />
            <el-table-column prop="ASSET_COST" label="原值" width="110" />
            <el-table-column prop="ACCUM_DEPR" label="累计折旧" width="110" />
            <el-table-column prop="IMPAIRMENT" label="减值" width="90" />
            <el-table-column prop="NET_VALUE" label="净值" width="110" />
            <el-table-column prop="LIFE_YEARS" label="年限" width="70" />
            <el-table-column prop="DEPR_STATUS" label="折旧状态" width="150" />
          </el-table>
          <div style="margin-top:8px;color:#909399">
            本页合计：原值 {{ ledgerPage.pageCost }} / 累计 {{ ledgerPage.pageAccum }} /
            净值 {{ ledgerPage.pageNet }}（共 {{ ledgerPage.total }} 张卡，不含处置/在建/归档卡）
          </div>
        </template>
      </el-tab-pane>

      <!-- ================= 7.8.3 盘点报告 ================= -->
      <el-tab-pane label="盘点报告" name="count">
        <el-form inline>
          <el-input v-model="reportQuery.keyword" clearable placeholder="盘点单号" style="width:180px" />
          <el-button type="primary" @click="loadReports">查询</el-button>
        </el-form>
        <el-table :data="reportRows" border>
          <el-table-column prop="cntNo" label="盘点单号" width="170" />
          <el-table-column prop="countType" label="类型" width="90" />
          <el-table-column prop="baseDate" label="基准日" width="110" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column prop="coverageRate" label="覆盖率" width="100" />
          <el-table-column label="报告" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.reportReady" type="success" size="small">已归档</el-tag>
              <el-tag v-else type="info" size="small">报告未生成</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button size="small" @click="openReport(row.id)">查看报告</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next" :total="reportTotal"
                       :page-size="reportQuery.size" :current-page="reportQuery.current"
                       @current-change="onreportPage" style="margin-top:10px" />
      </el-tab-pane>
    </el-tabs>

    <!-- 报告详情抽屉 -->
    <el-drawer v-model="reportVisible" title="盘点总结报告" size="680px">
      <template v-if="report">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="盘点单号">{{ report.cntNo }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ report.countType }}</el-descriptions-item>
          <el-descriptions-item label="基准日">{{ report.baseDate }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ report.status }}</el-descriptions-item>
          <el-descriptions-item label="覆盖率">{{ report.coverageRate }}</el-descriptions-item>
          <el-descriptions-item v-if="report.reportReady" label="差异率">{{ report.diffRate }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="!report.reportReady">
          <el-alert type="info" :closable="false" title="报告未生成——结案提交并经审批归档后生成总结报告"
                    style="margin-top:12px" />
          <div style="margin-top:10px">进度：已盘 {{ report.progress?.countedQty ?? 0 }} / 应盘 {{ report.progress?.totalQty ?? 0 }}</div>
        </template>
        <template v-else>
          <h4 style="margin:14px 0 6px">差异分布（{{ report.diffCount }} 笔 / ¥{{ report.diffAmount }}）</h4>
          <el-table :data="report.diffByType" border size="small">
            <el-table-column prop="DIFF_TYPE" label="差异类型" width="160">
              <template #default="{ row }">{{ DIFF_TYPES[row.DIFF_TYPE] || row.DIFF_TYPE }}</template>
            </el-table-column>
            <el-table-column prop="CNT" label="笔数" width="90" />
            <el-table-column prop="AMT" label="金额" />
          </el-table>
          <h4 style="margin:14px 0 6px">分类 TOP</h4>
          <el-table :data="report.topByCategory" border size="small">
            <el-table-column prop="DIM" label="分类" />
            <el-table-column prop="CNT" label="笔数" width="90" />
            <el-table-column prop="AMT" label="金额" />
          </el-table>
          <h4 style="margin:14px 0 6px">部门 TOP</h4>
          <el-table :data="report.topByDept" border size="small">
            <el-table-column prop="DIM" label="部门" />
            <el-table-column prop="CNT" label="笔数" width="90" />
            <el-table-column prop="AMT" label="金额" />
          </el-table>
          <h4 style="margin:14px 0 6px">差异原因</h4>
          <el-table :data="report.reasons" border size="small">
            <el-table-column prop="REASON" label="原因" />
            <el-table-column prop="CNT" label="笔数" width="90" />
          </el-table>
          <h4 style="margin:14px 0 6px">归档摘要（REPORT_SUMMARY）</h4>
          <div style="white-space:pre-wrap;color:#606266">{{ report.reportSummary }}</div>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  changeEvents, changeSummary, depSummary, depDetails, netLedger,
  countReports, countReportDetail,
  exportChangeCsv, exportDepreciationCsv, exportLedgerCsv
} from '@/api/eam/report'

const EVT_TYPES = {
  CAPITALIZATION: '转资建卡', DISPOSAL: '处置出账', REWORK_REBUILD: '技改重建',
  TRANSFER: '调拨完成', COUNT_ADJUST: '盘点调整', IMPAIRMENT: '减值', POLICY: '政策变更'
}
const DIFF_TYPES = { SHORT: '盘亏', LONG: '盘盈', STATUS_CONFLICT: '状态冲突', TAG_ISSUE: '无标签' }

const route = useRoute()
const tab = ref(route.meta?.tab || 'change')   // 7.8.1/7.8.2/7.8.3 三路由同组件按 meta 切 tab

// ---------- 7.8.1 ----------
const changeRange = ref([])
const changeQuery = reactive({ current: 1, size: 20, evtType: '', costCenter: '' })
const changeRows = ref([])
const changeTotal = ref(0)
const summary = ref({})

function changeParams() {
  return {
    fromDate: changeRange.value?.[0] || undefined,
    toDate: changeRange.value?.[1] || undefined,
    evtType: changeQuery.evtType || undefined,
    costCenter: changeQuery.costCenter || undefined
  }
}
async function loadChange() {
  const p = { ...changeParams(), current: changeQuery.current, size: changeQuery.size }
  const { data } = await changeEvents(p)
  changeRows.value = data.records || []
  changeTotal.value = Number(data.total || 0)
  const s = await changeSummary(changeParams())
  summary.value = s.data || {}
}
const onchangePage = (p) => { changeQuery.current = p; loadChange() }

// ---------- 7.8.2 ----------
const depPeriod = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const depView = ref('category')
const depSummaryRows = ref([])
const depSummaryHead = ref({})
const depDetailRows = ref([])
const ledgerQuery = reactive({ deprStatus: '' })
const ledgerRows = ref([])
const ledgerPage = ref({})

async function loadDep() {
  const { data } = await depSummary({ periodTag: depPeriod.value || undefined })
  depSummaryHead.value = data.run || {}
  depSummaryRows.value = data[depView.value === 'costCenter' ? 'byCostCenter' : 'byCategory'] || []
  if (depView.value === 'detail') {
    const d = await depDetails({ periodTag: depPeriod.value || undefined })
    depDetailRows.value = d.data || []
  }
}
async function loadLedger() {
  const { data } = await netLedger({
    current: 1, size: 100,
    deprStatus: ledgerQuery.deprStatus || undefined
  })
  ledgerRows.value = data.records || []
  ledgerPage.value = data
}
function onViewChange(v) {
  depView.value = v
  if (v === 'detail') {
    depDetails({ periodTag: depPeriod.value || undefined }).then((d) => { depDetailRows.value = d.data || [] })
  } else if (v === 'ledger') {
    loadLedger()
  } else {
    loadDep()
  }
}

// ---------- 7.8.3 ----------
const reportQuery = reactive({ current: 1, size: 20, keyword: '' })
const reportRows = ref([])
const reportTotal = ref(0)
const reportVisible = ref(false)
const report = ref(null)

async function loadReports() {
  const { data } = await countReports({
    current: reportQuery.current, size: reportQuery.size, keyword: reportQuery.keyword || undefined
  })
  reportRows.value = data.records || []
  reportTotal.value = Number(data.total || 0)
}
const onreportPage = (p) => { reportQuery.current = p; loadReports() }
async function openReport(id) {
  const { data } = await countReportDetail(id)
  report.value = data
  reportVisible.value = true
}

// ---------- 导出 ----------
function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = filename; a.click()
  URL.revokeObjectURL(url)
}
async function doExportChange() {
  try {
    const res = await exportChangeCsv(changeParams())
    saveBlob(res, 'asset-change-events.csv')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '导出失败（超限请缩小范围）')
  }
}
async function doExportDep() {
  const res = await exportDepreciationCsv({ periodTag: depPeriod.value || undefined })
  saveBlob(res, `asset-depreciation-${depPeriod.value}.csv`)
}
async function doExportLedger() {
  const res = await exportLedgerCsv({ deprStatus: ledgerQuery.deprStatus || undefined })
  saveBlob(res, 'asset-net-ledger.csv')
}

onMounted(() => {
  loadChange()
  loadDep()
  loadReports()
})
</script>

<style scoped>
:deep(.el-radio-button__inner) { padding: 7px 12px; }
</style>
