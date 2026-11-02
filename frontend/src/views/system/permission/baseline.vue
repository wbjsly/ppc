<template>
  <div class="perm-page">
    <!-- ================= 16.2.3 岗位基线 ================= -->
    <div class="toolbar">
      <el-input v-model="baselineKeyword" placeholder="岗位名称/编码" clearable
        style="width: 220px" @keyup.enter="loadBaselines" @clear="loadBaselines" />
      <el-button type="primary" @click="loadBaselines">查询</el-button>
      <el-tag type="warning" effect="plain">无基线岗位建号不授予任何角色</el-tag>
    </div>
    <el-table :data="baselines" v-loading="baselineLoading" border stripe>
      <el-table-column prop="posNo" label="岗位编码" width="140" />
      <el-table-column prop="posName" label="岗位名称" min-width="150" />
      <el-table-column prop="deptName" label="所属部门" width="140" />
      <el-table-column label="基线角色" min-width="260">
        <template #default="{ row }">
          <template v-if="row.roles && row.roles.length">
            <el-tag v-for="rc in row.roles" :key="rc" size="small"
              :type="isPrivileged(rc) ? 'danger' : 'default'"
              style="margin-right: 4px">{{ rc }}</el-tag>
          </template>
          <el-tag v-else-if="!row.hasBaseline" type="warning" effect="plain">未配置</el-tag>
          <span v-else class="muted">（空）</span>
        </template>
      </el-table-column>
      <el-table-column label="违规" width="150">
        <template #default="{ row }">
          <el-tag v-if="row.violations && row.violations.length" type="danger">
            {{ row.violations.join(',') }}
          </el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openBaselineEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 岗位基线编辑对话框 -->
    <el-dialog v-model="baselineDlg" :title="'编辑基线：' + baselineForm.posName" width="520px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px"
        title="基线禁含审批权/金额权角色（L1 硬阻断，保存时校验）" />
      <el-select v-model="baselineForm.roles" multiple filterable allow-create default-first-option
        placeholder="选择或输入角色码" style="width: 100%">
        <el-option v-for="r in matrixRoles" :key="r.roleCode" :label="r.roleCode + '（' + r.roleName + '）'"
          :value="r.roleCode" />
      </el-select>
      <template #footer>
        <el-button @click="baselineDlg = false">取消</el-button>
        <el-button type="primary" @click="submitBaseline">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 16.2.3 岗位基线（拆分自原权限管理操作台）：岗位基线台账 / 编辑（禁含特权角色）。
 * 共享角色全量列表（弹窗选项）见 usePermission.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/system/permission'
import { usePermission } from './usePermission'

const { matrixRoles, loadMatrixRoles } = usePermission()

const baselines = ref([])
const baselineLoading = ref(false)
const baselineKeyword = ref('')
const baselineDlg = ref(false)
const baselineForm = ref({ posId: '', posName: '', roles: [] })
const PRIVILEGED = new Set([
  'ROLE_ADMIN', 'ROLE_ASSET_ADMIN', 'ROLE_COMPLIANCE', 'ROLE_DEPT_HEAD', 'ROLE_EQUIP_MGR',
  'ROLE_EXEC', 'ROLE_FINANCE', 'ROLE_FINANCE_MGR', 'ROLE_FIN_ASSET', 'ROLE_FIN_MGR', 'ROLE_GM',
  'ROLE_HR', 'ROLE_HR_MGR', 'ROLE_PLAN_MGR', 'ROLE_PM', 'ROLE_PMGR', 'ROLE_PMO',
  'ROLE_PROCESS_MGR', 'ROLE_PURCHASER', 'ROLE_QUALITY_MGR', 'ROLE_SALES_DIRECTOR',
  'ROLE_SALES_MGR', 'ROLE_TECH_OWNER', 'ROLE_WAREHOUSE'
])
function isPrivileged(code) { return PRIVILEGED.has(code) }

async function loadBaselines() {
  baselineLoading.value = true
  try {
    const { data } = await api.baselineList({ keyword: baselineKeyword.value || undefined })
    baselines.value = data || []
  } finally {
    baselineLoading.value = false
  }
}
function openBaselineEdit(row) {
  baselineForm.value = { posId: row.posId, posName: row.posName, roles: [...(row.roles || [])] }
  baselineDlg.value = true
}
async function submitBaseline() {
  await api.saveBaseline(baselineForm.value.posId, baselineForm.value.roles)
  ElMessage.success('基线已保存')
  baselineDlg.value = false
  loadBaselines()
}

onMounted(async () => {
  await Promise.all([loadBaselines(), loadMatrixRoles()])
})
</script>

<style scoped>
.perm-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; }
</style>
