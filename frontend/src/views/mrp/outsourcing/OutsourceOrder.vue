<template>
  <div>
    <el-page-header content="外协订单（5.10.1）— 外协派工单聚合工作台：按回联状态分组，维护外协交期/备注/供料方式（外协订单 = 外协派工单，D11）"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-select v-model="filters.linkStatus" size="small" clearable placeholder="回联状态" style="width: 140px">
        <el-option label="待下单" value="PENDING_PR" />
        <el-option label="已下单" value="ORDERED" />
        <el-option label="已收货" value="RECEIVED" />
      </el-select>
      <el-select v-model="filters.supplierCode" size="small" clearable filterable placeholder="供应商"
                 style="width: 190px">
        <el-option v-for="s in suppliers" :key="s.supplierCode"
                   :label="`${s.supplierCode}（${s.supplierName}）`" :value="s.supplierCode" />
      </el-select>
      <el-input v-model="filters.keyword" size="small" clearable placeholder="单号/工单号/PO 号"
                style="width: 190px" @keyup.enter="load" />
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <span class="tip">回联状态由 PR/PO/收货自动推导；5.6.3 改派审批通过后在此维护交期与供料方式</span>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small" max-height="560">
      <el-table-column prop="dispatchNo" label="派工单号" width="160" />
      <el-table-column prop="moNo" label="工单号" width="140" />
      <el-table-column label="工序" width="130">
        <template #default="{ row }">{{ row.opSeq }} {{ row.opName }}</template>
      </el-table-column>
      <el-table-column prop="productName" label="加工物料" min-width="130" show-overflow-tooltip />
      <el-table-column label="数量" width="80" align="right">
        <template #default="{ row }">{{ row.qty }}</template>
      </el-table-column>
      <el-table-column prop="wcName" label="外协工作中心" width="140" show-overflow-tooltip />
      <el-table-column label="供应商" width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.supplierCode">{{ row.supplierCode }}（{{ row.supplierName }}）</span>
          <span v-else style="color: #909399">未维护</span>
        </template>
      </el-table-column>
      <el-table-column label="回联状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="linkTag(row.linkStatus)" size="small">{{ linkText(row.linkStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="prNo" label="PR 号" width="130" show-overflow-tooltip />
      <el-table-column prop="poNo" label="PO 号" width="130" show-overflow-tooltip />
      <el-table-column label="外协交期" width="110" align="center">
        <template #default="{ row }">{{ row.osDueDate || '-' }}</template>
      </el-table-column>
      <el-table-column label="供料方式" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.osSupplyType === 'COMPANY_SUPPLY' ? 'warning' : 'info'" size="small">
            {{ row.osSupplyType === 'COMPANY_SUPPLY' ? '甲供' : '自购' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openEdit(row)">维护</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无外协派工单（先在 5.6.3 发起外协改派）"
              :image-size="60" />

    <!-- 维护弹窗 -->
    <el-dialog v-model="editVisible" title="维护外协字段" width="460px">
      <el-form label-width="90px" size="small">
        <el-form-item label="派工单号">
          <span>{{ form.dispatchNo }}</span>
        </el-form-item>
        <el-form-item label="外协交期">
          <el-date-picker v-model="form.osDueDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="供应商交付承诺日" style="width: 100%" />
        </el-form-item>
        <el-form-item label="供料方式">
          <el-radio-group v-model="form.osSupplyType">
            <el-radio label="SUPPLIER_BUY">供应商自购</el-radio>
            <el-radio label="COMPANY_SUPPLY">甲供</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="外协备注">
          <el-input v-model="form.osRemark" type="textarea" :rows="3"
                    placeholder="供料/工艺等供应商侧约定" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="editVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getWorkbenchApi, maintainOsFieldsApi } from '@/api/mrp/outsourcing'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const suppliers = ref([])
const filters = reactive({ linkStatus: '', supplierCode: '', keyword: '' })

const editVisible = ref(false)
const form = reactive({ id: '', dispatchNo: '', osDueDate: '', osRemark: '', osSupplyType: 'SUPPLIER_BUY' })

const linkText = (s) => ({ PENDING_PR: '待下单', ORDERED: '已下单', RECEIVED: '已收货' }[s] || s)
const linkTag = (s) => ({ PENDING_PR: 'info', ORDERED: 'warning', RECEIVED: 'success' }[s] || 'info')

async function load() {
  loading.value = true
  try {
    const params = {}
    if (filters.linkStatus) params.linkStatus = filters.linkStatus
    if (filters.supplierCode) params.supplierCode = filters.supplierCode
    if (filters.keyword) params.keyword = filters.keyword
    const res = await getWorkbenchApi(params)
    rows.value = res.data || []
    // 供应商下拉选项取自当前结果集（去重）
    const seen = new Map()
    for (const r of rows.value) {
      if (r.supplierCode && !seen.has(r.supplierCode)) {
        seen.set(r.supplierCode, { supplierCode: r.supplierCode, supplierName: r.supplierName })
      }
    }
    suppliers.value = [...seen.values()]
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    dispatchNo: row.dispatchNo,
    osDueDate: row.osDueDate || '',
    osRemark: row.osRemark || '',
    osSupplyType: row.osSupplyType || 'SUPPLIER_BUY'
  })
  editVisible.value = true
}

async function save() {
  saving.value = true
  try {
    await maintainOsFieldsApi(form.id, {
      osDueDate: form.osDueDate || null,
      osRemark: form.osRemark,
      osSupplyType: form.osSupplyType
    })
    ElMessage.success('外协字段已保存')
    editVisible.value = false
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
