<template>
  <div class="page">
    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 130px">
          <el-option label="生效中" value="ACTIVE" />
          <el-option label="已解冻" value="RELEASED" />
          <el-option label="审批中" value="PENDING" />
        </el-select>
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="query.freezeType" clearable placeholder="全部" style="width: 120px">
          <el-option label="质量冻结" value="QUALITY" />
          <el-option label="财务冻结" value="FINANCE" />
        </el-select>
      </el-form-item>
      <el-form-item label="物料">
        <el-input v-model="query.itemCode" placeholder="物料编码" clearable style="width: 140px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">查询</el-button>
      </el-form-item>
      <span class="tip">
        冻结生效时生成影响面快照（SO=预留反查精确到批次 / PO=未清行物料级 / 工单数据源未落地），
        并落 FREEZE/UNFREEZE 通知记录（FR-4.4-5-4/5.5）。
      </span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe
      @row-click="(row) => openImpact(row)">
      <el-table-column prop="freezeNo" label="冻结单号" width="150" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.freezeType === 'QUALITY' ? 'warning' : 'danger'" size="small">
            {{ row.freezeType === 'QUALITY' ? '质量' : '财务' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="itemCode" label="物料" width="130" />
      <el-table-column prop="batchNo" label="批次" width="120">
        <template #default="{ row }">{{ row.batchNo || '全部批次' }}</template>
      </el-table-column>
      <el-table-column prop="qty" label="数量" width="90" align="right" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'danger'
            : row.status === 'RELEASED' ? 'success' : 'info'" size="small">
            {{ { PENDING: '审批中', ACTIVE: '生效中', RELEASED: '已解冻', REJECTED: '已驳回' }[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="影响快照" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.impactJson" type="primary" size="small">已生成</el-tag>
          <span v-else class="readonly-tip">未生成</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click.stop="openImpact(row)">查看影响</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 影响详情抽屉：三类清单 + 精度标签 + 通知记录 -->
    <el-drawer v-model="impactVisible" :title="`影响详情 ${current?.freezeNo || ''}`" size="620px">
      <template v-if="current">
        <el-alert v-if="!current.impactJson" type="info" :closable="false" style="margin-bottom: 12px"
          title="该冻结单尚未执行（审批中/驳回），暂无影响面快照" />
        <template v-else>
          <h4>受影响 SO <el-tag size="small" type="success">精确到批次</el-tag></h4>
          <el-table v-if="impact?.sos?.length" :data="impact.sos" size="small" border>
            <el-table-column prop="soNo" label="订单号" />
            <el-table-column prop="lineNo" label="行号" width="70" />
            <el-table-column prop="qty" label="预留量" width="100" align="right" />
          </el-table>
          <p v-else class="readonly-tip">无受影响订单（该维度无 ACTIVE 预留）</p>

          <h4>受影响 PO <el-tag size="small" type="warning">物料级</el-tag></h4>
          <el-table v-if="impact?.pos?.length" :data="impact.pos" size="small" border>
            <el-table-column prop="poNo" label="采购单号" />
            <el-table-column prop="lineNo" label="行号" width="70" />
            <el-table-column prop="qty" label="未清量" width="100" align="right" />
          </el-table>
          <p v-else class="readonly-tip">无未清 PO</p>

          <h4>受影响工单 <el-tag size="small" type="info">数据源未落地</el-tag></h4>
          <p class="readonly-tip">
            生产工单域未落地（proposal 偏差 D1），工单清单暂不可用（moDataSource=false）。
          </p>
        </template>

        <h4>通知记录</h4>
        <el-table v-if="notices.length" :data="notices" size="small" border>
          <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
          <el-table-column prop="createDate" label="时间" width="155">
            <template #default="{ row }">{{ fmtTime(row.createDate) }}</template>
          </el-table-column>
        </el-table>
        <p v-else class="readonly-tip">暂无通知记录</p>
      </template>
      <template #footer>
        <el-button @click="impactVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getFreezesApi } from '@/api/inv/freeze'
import request from '@/utils/request'

/** 我的站内通知（复用 /api/crm/notices 端点，按角色聚合） */
function getMyNoticesApi() {
  return request.get('/crm/notices')
}

// 4.9.4 影响通知：冻结单影响面快照三类清单（精度标签）+ FREEZE/UNFREEZE 通知记录。
// 数据源 = 既有 query 接口 impactJson 字段 + 站内通知（按 BIZ_ID 关联冻结单号）。
const loading = ref(false)
const rows = ref([])
const query = ref({ status: '', freezeType: '', itemCode: '' })

const impactVisible = ref(false)
const current = ref(null)
const impact = ref(null)
const notices = ref([])

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadData() {
  loading.value = true
  try {
    const params = {}
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    const res = await getFreezesApi(params)
    rows.value = res.data || []
  } catch (e) {
    console.warn('[freeze-impact] load failed', e)
  } finally {
    loading.value = false
  }
}

async function openImpact(row) {
  current.value = row
  impact.value = null
  notices.value = []
  impactVisible.value = true
  try {
    impact.value = row.impactJson ? JSON.parse(row.impactJson) : null
  } catch {
    impact.value = null
  }
  try {
    const res = await getMyNoticesApi()
    const all = res.data || []
    notices.value = all.filter(n =>
      (n.bizType === 'FREEZE' || n.bizType === 'UNFREEZE') && n.bizId === row.freezeNo)
  } catch (e) {
    console.warn('[freeze-impact] notices failed', e)
  }
}

onMounted(loadData)
</script>

<style scoped>
.page { padding: 4px; }
.filter-bar { margin-bottom: 4px; }
.tip { color: #909399; font-size: 12px; line-height: 32px; margin-left: 8px; }
.readonly-tip { color: #909399; font-size: 12px; }
h4 { margin: 12px 0 6px; display: flex; align-items: center; gap: 6px; }
</style>
