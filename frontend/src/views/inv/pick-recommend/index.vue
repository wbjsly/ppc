<template>
  <div class="pr-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="拣货推荐（4.6.3）：出库队列 A 单据 → 《批次推荐表》（批次+仓位）→ 确认回写"
      description="排序口径：入库日期升序（FIFO）→ 同日有效期升序（FEFO）→ 排除冻结与效期锁定（BR-4.4-19）。确认仅回写单据行批次/仓位，不生成预留；过账时按行值扣减，库存不足 422 封顶兜底（偏差 D2）。下发/扫码/复核归 4.7。" />

    <el-tabs v-model="type" @tab-change="onTypeChange">
      <el-tab-pane label="销售出库" name="SALES_OUT" />
      <el-tab-pane label="领料出库" name="MATERIAL_OUT" />
      <el-tab-pane label="调拨出库" name="TRANSFER_OUT" />
      <el-tab-pane label="报废出库" name="SCRAP_OUT" />
    </el-tabs>

    <el-row :gutter="12">
      <!-- 左：队列 A 单据 -->
      <el-col :span="8">
        <div class="panel-title">
          待推荐单据（队列 A）
          <el-button link type="primary" size="small" @click="loadQueue">刷新</el-button>
        </div>
        <el-table :data="queueRows" size="small" border height="420" v-loading="queueLoading"
          highlight-current-row @current-change="onSelectDoc">
          <el-table-column prop="docNo" label="单号" width="150" />
          <el-table-column prop="status" label="状态" width="80" />
          <el-table-column prop="itemSummary" label="物料" min-width="120" show-overflow-tooltip />
        </el-table>
      </el-col>

      <!-- 中：推荐明细（可改写） -->
      <el-col :span="10">
        <div class="panel-title">《批次推荐表》（逐行可改写）</div>
        <el-alert v-if="!selected" type="info" :closable="false" title="请先在左侧选择单据"
          style="margin-bottom: 8px;" />
        <template v-else>
          <div style="margin-bottom: 8px;">
            <el-button type="primary" size="small" :loading="genLoading"
              @click="generate">生成推荐</el-button>
            <span class="hint">{{ selected.docNo }} · {{ typeLabel }}</span>
          </div>
          <div v-for="line in recLines" :key="line.lineNo" class="line-card">
            <div class="line-head">
              <span>行 {{ line.lineNo }} · {{ line.itemCode }} · 需求 {{ line.qty }}</span>
              <el-tag v-if="line.satisfied === false" size="small" type="danger">
                缺口 {{ line.gap }}
              </el-tag>
            </div>
            <div v-if="line.recommendLines && line.recommendLines.length" class="line-rec">
              <div v-for="(r, ri) in line.recommendLines" :key="ri" class="rec-row">
                <span class="rec-batch">{{ r.batchNo }}</span>
                <span class="rec-qty">取 {{ r.take }} / 可用 {{ r.available }}</span>
                <el-tag v-if="r.expiryWarning" size="small" type="warning">效期警告</el-tag>
                <el-select v-model="line.binPicks[ri]" placeholder="仓位" size="small"
                  clearable style="width: 130px; margin-left: 6px;">
                  <el-option v-for="b in (r.bins || [])" :key="b.binCode"
                    :label="(b.binCode || '未分配位') + '（取 ' + b.take + '）'"
                    :value="b.binCode" />
                </el-select>
              </div>
              <div class="rec-edit">
                <el-input v-model="line.editBatch" size="small" placeholder="改写批次（默认=推荐）"
                  clearable style="width: 170px;" />
                <el-input v-model="line.editReason" size="small" placeholder="改批原因（改批必填）"
                  clearable style="width: 200px;" />
              </div>
            </div>
            <el-alert v-else type="error" :closable="false" :title="line.splitSuggestion || '无可用批次'"
              size="small" />
            <div v-if="line.excluded && line.excluded.length" class="line-excluded">
              剔除：{{ line.excluded.map(e => e.batchNo + '(' + e.reason + ')').join('；') }}
            </div>
          </div>
        </template>
      </el-col>

      <!-- 右：确认 -->
      <el-col :span="6">
        <div class="panel-title">确认回写</div>
        <el-alert type="warning" :closable="false" style="margin-bottom: 8px;"
          title="保守版语义" description="① 只回写行批次/仓位，不建预留；② 改写行强制记录偏离台账（原因必填）；③ 过账时按行值扣减，不足 422。" />
        <el-button type="primary" :disabled="!recLines.length" :loading="confirmLoading"
          style="width: 100%;" @click="confirm">确认回写</el-button>
        <el-result v-if="confirmResult" icon="success" title="回写成功"
          :sub-title="`更新 ${confirmResult.updatedLines} 行，偏离留痕 ${confirmResult.deviationLines} 行`"
          style="padding: 10px 0;" />
        <div class="sort-rule" v-if="recLines.length && recLines[0].sortRule">
          {{ recLines[0].sortRule }}
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getOutboundQueueApi } from '@/api/inv/outbound-workbench'
import { generatePickRecommendApi, confirmPickRecommendApi } from '@/api/inv/fifo'

const type = ref('SALES_OUT')
const typeLabel = {
  SALES_OUT: '销售出库', MATERIAL_OUT: '领料出库',
  TRANSFER_OUT: '调拨出库', SCRAP_OUT: '报废出库'
}

const queueLoading = ref(false)
const queueRows = ref([])
const selected = ref(null)
const genLoading = ref(false)
const recLines = ref([])
const confirmLoading = ref(false)
const confirmResult = ref(null)

async function loadQueue() {
  queueLoading.value = true
  selected.value = null
  recLines.value = []
  confirmResult.value = null
  try {
    const res = await getOutboundQueueApi(type.value, { queue: 'A', current: 1, size: 50 })
    // 作业台队列返回字段为 rows（与 outbound-workbench 页面同口径）
    queueRows.value = res.data.rows || res.data.records || []
  } finally {
    queueLoading.value = false
  }
}

function onTypeChange() {
  loadQueue()
}

function onSelectDoc(row) {
  selected.value = row
  recLines.value = []
  confirmResult.value = null
}

async function generate() {
  genLoading.value = true
  confirmResult.value = null
  try {
    const res = await generatePickRecommendApi({ type: type.value, docNo: selected.value.docNo })
    const lines = res.data.lines || []
    // 初始化可编辑态
    for (const l of lines) {
      l.editBatch = ''
      l.editReason = ''
      l.binPicks = {}
    }
    recLines.value = lines
  } catch (e) {
    ElMessage.error((e && e.message) || '生成推荐失败')
  } finally {
    genLoading.value = false
  }
}

async function confirm() {
  // 前端预检：改批必填原因（后端 422 兜底）
  const reqLines = []
  for (const l of recLines.value) {
    const recBatch = (l.recommendLines && l.recommendLines.length)
      ? l.recommendLines[0].batchNo : ''
    const wantBatch = (l.editBatch || '').trim() || recBatch
    if (!wantBatch) {
      ElMessage.error(`行 ${l.lineNo} 无推荐批次且未指定批次`)
      return
    }
    const changed = recBatch && wantBatch !== recBatch
    const reason = (l.editReason || '').trim()
    if (changed && !reason) {
      ElMessage.error(`行 ${l.lineNo} 批次由 ${recBatch} 改写为 ${wantBatch}，请填写改批原因`)
      return
    }
    // 仓位：取第一个非空选择
    const bins = Object.values(l.binPicks || {}).filter(Boolean)
    reqLines.push({
      lineNo: l.lineNo,
      batchNo: wantBatch,
      binCode: bins.length ? bins[0] : undefined,
      deviationReason: changed ? reason : undefined
    })
  }
  confirmLoading.value = true
  try {
    const res = await confirmPickRecommendApi({
      type: type.value, docNo: selected.value.docNo, lines: reqLines
    })
    confirmResult.value = res.data
    ElMessage.success('回写成功（不建预留，过账时按行值扣减）')
  } catch (e) {
    ElMessage.error((e && e.message) || '确认回写失败')
  } finally {
    confirmLoading.value = false
  }
}

onMounted(loadQueue)
</script>

<style scoped>
.pr-page { padding: 12px; }
.panel-title { font-size: 13px; font-weight: 600; color: #606266; margin-bottom: 8px; }
.hint { font-size: 12px; color: #909399; margin-left: 8px; }
.line-card { border: 1px solid #ebeef5; border-radius: 4px; padding: 8px; margin-bottom: 8px; }
.line-head { font-size: 13px; font-weight: 600; margin-bottom: 6px; display: flex;
  justify-content: space-between; align-items: center; }
.rec-row { display: flex; align-items: center; font-size: 12px; margin-bottom: 4px; }
.rec-batch { font-weight: 600; color: #409EFF; width: 120px; }
.rec-qty { color: #606266; }
.rec-edit { display: flex; gap: 6px; margin-top: 6px; }
.line-excluded { font-size: 12px; color: #F56C6C; margin-top: 6px; }
.sort-rule { font-size: 12px; color: #909399; margin-top: 10px; line-height: 1.6; }
</style>
