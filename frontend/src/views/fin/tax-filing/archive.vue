<template>
  <div class="page">
    <div class="head">
      <span class="title">申报归档（按所属期聚合：申报表 + 栏次 + 回执/缴款 + 调整记录）</span>
      <div>
        <el-input v-model="period" placeholder="所属期 yyyyMM" style="width: 130px; margin-right: 8px" size="small" />
        <el-button type="primary" size="small" @click="load">查询归档</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" class="mb8"
      title="保存期限 ≥10 年（FR-4.14-5-7）；仅归集已申报（FILED/PAID）产物，未申报期无归档记录" />

    <el-empty v-if="loaded && archive && archive.count === 0" :description="`所属期 ${period} 无已申报归档产物`" />

    <template v-if="archive && archive.items && archive.items.length">
      <el-card v-for="item in archive.items" :key="item.returnNo" shadow="never" class="mb8">
        <template #header>
          <div class="card-head">
            <span>
              <el-tag size="small" :type="item.taxType === 'VAT' ? 'primary' : 'warning'">
                {{ item.taxType === 'VAT' ? '增值税' : '所得税预缴' }}</el-tag>
              <b style="margin-left: 8px">{{ item.returnNo }}</b>
              <span class="muted" style="margin-left: 8px">保存期限 ≥{{ archive.retentionYears }} 年</span>
            </span>
            <span>
              <el-tag size="small" type="success">{{ item.status === 'PAID' ? '已缴款' : '已申报' }}</el-tag>
            </span>
          </div>
        </template>
        <el-descriptions :column="4" size="small" border>
          <el-descriptions-item label="应纳税额">{{ item.taxType === 'VAT' ? item.payableTax : item.citDue }}</el-descriptions-item>
          <el-descriptions-item label="回执编号">{{ item.receiptNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="申报成功日">{{ item.fileDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="缴款">{{ item.payAmount || '—' }}（{{ item.payDate || '' }}）</el-descriptions-item>
        </el-descriptions>
        <el-collapse style="margin-top: 8px">
          <el-collapse-item name="lines">
            <template #title>栏次明细（{{ item.lines.length }} 条）</template>
            <el-table :data="item.lines" size="small" border max-height="260">
              <el-table-column prop="lineCode" label="栏次编码" width="160" />
              <el-table-column prop="lineName" label="栏次" width="150" />
              <el-table-column prop="amount" label="金额" width="110" />
              <el-table-column prop="sourceDesc" label="来源" show-overflow-tooltip />
            </el-table>
          </el-collapse-item>
          <el-collapse-item v-if="item.adjustments && item.adjustments.length" name="adjusts">
            <template #title>跨期调整记录（{{ item.adjustments.length }} 条，BR-4.14-31 留痕）</template>
            <el-table :data="item.adjustments" size="small" border>
              <el-table-column prop="adjNo" label="调整单号" width="170" />
              <el-table-column prop="reason" label="原因" show-overflow-tooltip />
              <el-table-column prop="createBy" label="发起人" width="90" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { taxArchive } from '@/api/fin/tax-filing'

const period = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const archive = ref(null)
const loaded = ref(false)

async function load() {
  loaded.value = false
  try {
    const { data } = await taxArchive(period.value)
    archive.value = data
  } catch (e) { /* ignore */ }
  loaded.value = true
}

onMounted(load)
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.title { font-size: 15px; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
