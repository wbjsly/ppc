<template>
  <div>
    <el-page-header content="权限控制（14.4）— 行级三维度矩阵/多角色并集（BR-4.10-16）/异常回退（BR-4.10-17）；列级敏感字段登记；掩码总览与临时授权（≤24h）"
                    style="margin-bottom: 8px" />

    <!-- ==================== 14.4.3 字段掩码 ==================== -->
    <el-alert type="info" show-icon :closable="false" style="margin-bottom: 8px"
              title="生效掩码规则总览（复用 14.2.2 规则管理）+ 效果预览 + 临时授权申请（≤24h 到期自动收回，spec bi-temp-grant）" />

    <h4>生效规则总览</h4>
    <el-table :data="maskRules" border stripe size="small" max-height="200">
      <el-table-column prop="targetKey" label="字段（*=通配）" min-width="140" />
      <el-table-column prop="maskStyle" label="样式" width="100" />
      <el-table-column prop="roleCode" label="适用角色" width="140" />
      <el-table-column prop="status" label="状态" width="110" align="center" />
    </el-table>

    <h4 style="margin-top: 14px">掩码效果预览（纯前端计算，不查库）</h4>
    <div class="toolbar">
      <el-select v-model="previewStyle" size="small" style="width: 130px">
        <el-option v-for="s in ['PHONE', 'ID_CARD', 'SALARY', 'BANK', 'GENERIC']"
                   :key="s" :label="s" :value="s" />
      </el-select>
      <el-input v-model="previewValue" placeholder="输入原始值" size="small" style="width: 200px" />
      <el-tag type="warning" size="large">→ {{ maskPreview }}</el-tag>
    </div>

    <h4 style="margin-top: 14px">临时授权申请（ROLE_DATA_GOV 审批，≤24h）</h4>
    <div class="toolbar">
      <el-input v-model="grantForm.fieldKey" placeholder="申请字段" size="small" style="width: 150px" />
      <el-input v-model="grantForm.purpose" placeholder="用途说明（≥5 字）" size="small"
                style="width: 240px" />
      <el-input-number v-model="grantForm.hours" :min="1" :max="24" size="small" />
      <el-button size="small" type="success" @click="onApplyGrant">提交申请</el-button>
    </div>
    <el-table :data="grants" border stripe size="small" max-height="220">
      <el-table-column prop="fieldKey" label="字段" width="140" />
      <el-table-column prop="userName" label="申请人" width="110" />
      <el-table-column prop="purpose" label="用途" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="120" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success'
            : row.status === 'PENDING_APPROVAL' ? 'warning'
            : row.status === 'REJECTED' ? 'danger' : 'info'" size="small">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="expireAt" label="到期时间" width="160" />
      <el-table-column label="操作" width="100" align="center">
        <template #default="{ row }">
          <el-button v-if="row.status === 'ACTIVE'" size="small" type="danger" link
                     @click="onRevokeGrant(row)">收回</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 14.4.3 字段掩码（拆分自原权限控制操作台）：生效掩码规则总览 / 效果预览 / 临时授权申请与收回。
 * 三页逻辑互不共享，全部留在页内（design D2：单 tab 独有逻辑不强抽）。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getMaskRulesApi, applyTempGrantApi, listMyTempGrantsApi, revokeTempGrantApi
} from '@/api/bi/perm-control'

const maskRules = ref([])
const previewStyle = ref('PHONE')
const previewValue = ref('13812345678')
const grantForm = ref({ fieldKey: '', purpose: '', hours: 4 })
const grants = ref([])

const maskPreview = computed(() => {
  const s = previewValue.value || ''
  switch (previewStyle.value) {
    case 'SALARY': return '***'
    case 'BANK': return s.length <= 4 ? '***' : '****' + s.slice(-4)
    case 'PHONE':
    case 'ID_CARD': return s.length <= 7 ? '**' : s.slice(0, 3) + '****' + s.slice(-4)
    default: return s.length <= 2 ? '**' : s[0] + '***' + s[s.length - 1]
  }
})

async function loadMaskData() {
  const res = await getMaskRulesApi()
  maskRules.value = (res.data || []).filter(r => r.status === 'ENABLED')
  const g = await listMyTempGrantsApi()
  grants.value = g.data || []
}

async function onApplyGrant() {
  try {
    await applyTempGrantApi(grantForm.value)
    ElMessage.success('已提交申请（ROLE_DATA_GOV 审批，有效期 ≤24h）')
    grantForm.value = { fieldKey: '', purpose: '', hours: 4 }
    loadMaskData()
  } catch (e) {
    ElMessage.error(e?.message || '申请失败')
  }
}

async function onRevokeGrant(row) {
  await revokeTempGrantApi(row.id)
  ElMessage.success('已收回')
  loadMaskData()
}

onMounted(() => {
  loadMaskData()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
h4 {
  margin: 10px 0 6px;
}
</style>
