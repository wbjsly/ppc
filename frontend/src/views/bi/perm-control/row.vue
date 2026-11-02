<template>
  <div>
    <el-page-header content="权限控制（14.4）— 行级三维度矩阵/多角色并集（BR-4.10-16）/异常回退（BR-4.10-17）；列级敏感字段登记；掩码总览与临时授权（≤24h）"
                    style="margin-bottom: 8px" />

    <!-- ==================== 14.4.1 行级权限 ==================== -->
    <el-alert type="info" show-icon :closable="false" style="margin-bottom: 8px"
              title="三维度（法人主体/组织部门树/数据密级）×（用户/角色）；SCOPE_EXPR 受控枚举 SELF/SELF_SUBTREE/LIST；变更经数据治理专员审批后生效并留版本快照" />

    <div class="toolbar">
      <el-input v-model="rowFilter.userName" placeholder="按用户过滤" size="small"
                clearable style="width: 150px" @change="loadRows" />
      <el-input v-model="rowFilter.roleCode" placeholder="按角色过滤" size="small"
                clearable style="width: 150px" @change="loadRows" />
      <el-button size="small" type="success" @click="openRowEdit(null)">新建规则</el-button>
      <el-button size="small" @click="loadRows">刷新</el-button>
    </div>
    <el-table :data="rowRules" border stripe size="small" max-height="300">
      <el-table-column label="授权对象" min-width="150">
        <template #default="{ row }">
          <el-tag v-if="row.userName" size="small">用户</el-tag>
          <el-tag v-else size="small" type="warning">角色</el-tag>
          {{ row.userName || row.roleCode }}
        </template>
      </el-table-column>
      <el-table-column prop="scopeExpr" label="范围表达式" width="120" />
      <el-table-column prop="orgId" label="组织锚" width="110" show-overflow-tooltip />
      <el-table-column prop="entityId" label="法人主体" width="110" />
      <el-table-column prop="secLevel" label="密级上限" width="80" />
      <el-table-column label="动作" width="70" align="center">
        <template #default="{ row }">
          <el-tag :type="row.action === 'DENY' ? 'danger' : 'success'" size="small">
            {{ row.action }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success'
            : row.status === 'PENDING_APPROVAL' ? 'warning' : 'info'" size="small">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" align="center">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT' || row.status === 'DISABLED'"
                     size="small" type="primary" link @click="openRowEdit(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT' || row.status === 'DISABLED'"
                     size="small" type="success" link @click="onSubmitRow(row)">提交审批</el-button>
          <el-button v-if="row.status === 'ENABLED'"
                     size="small" type="danger" link @click="onDisableRow(row)">停用</el-button>
          <el-button size="small" type="primary" link @click="showVersions(row)">版本</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4 style="margin-top: 14px">生效影响预览（多角色并集 + 取严后最终范围，BR-4.10-16）</h4>
    <div class="toolbar">
      <el-input v-model="previewUser" placeholder="输入用户名" size="small" style="width: 160px" />
      <el-button size="small" type="primary" @click="onPreview">预览</el-button>
    </div>
    <el-alert v-if="preview.allEntities !== undefined"
              :type="preview.allEntities ? 'success' : (preview.allowedEntities === 'ALL' ? 'success' : 'info')"
              :title="preview.allEntities
                ? '全量可见（通配 PASS 且无 DENY）'
                : `可见法人主体：${(preview.allowedEntities || []).join(', ') || '（空集=默认拒绝）'}`"
              show-icon :closable="false" style="margin-bottom: 6px" />
    <el-table v-if="preview.sources && Object.keys(preview.sources).length"
              :data="sourceRows" border size="small" max-height="160">
      <el-table-column prop="entity" label="法人主体" width="140" />
      <el-table-column prop="source" label="生效规则来源（审计追溯）" min-width="260" show-overflow-tooltip />
    </el-table>
    <el-alert v-if="preview.fallbackNotes && preview.fallbackNotes.length"
              type="error" show-icon :closable="false" style="margin-top: 6px"
              :title="`规则异常回退（BR-4.10-17）：${preview.fallbackNotes.join('；')}`" />

    <!-- 行级规则编辑对话框 -->
    <el-dialog v-model="rowVisible" :title="rowForm.id ? '编辑行级规则' : '新建行级规则'" width="560px">
      <el-form label-width="110px" size="small">
        <el-form-item label="授权对象类型">
          <el-radio-group v-model="rowForm.subjectType">
            <el-radio label="user">用户</el-radio>
            <el-radio label="role">角色</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="rowForm.subjectType === 'user' ? '用户名' : '角色编码'">
          <el-input v-model="rowForm.subject" :placeholder="rowForm.subjectType === 'user' ? '如 zhangsan' : '如 ROLE_SALES'" />
        </el-form-item>
        <el-form-item label="范围表达式">
          <el-select v-model="rowForm.scopeExpr" style="width: 100%">
            <el-option label="SELF（仅直配法人主体）" value="SELF" />
            <el-option label="SELF_SUBTREE（本部门及下级）" value="SELF_SUBTREE" />
            <el-option label="LIST（显式组织清单）" value="LIST" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rowForm.scopeExpr === 'SELF_SUBTREE'" label="组织锚 ID">
          <el-input v-model="rowForm.orgId" placeholder="组织单元 ID（树根）" />
        </el-form-item>
        <el-form-item v-if="rowForm.scopeExpr === 'LIST'" label="组织清单 JSON">
          <el-input v-model="rowForm.scopeList" type="textarea" :rows="2"
                    placeholder='["org-id-1","org-id-2"]' />
        </el-form-item>
        <el-form-item v-if="rowForm.scopeExpr === 'SELF'" label="法人主体">
          <el-input v-model="rowForm.entityId" placeholder="如 LE-01（*= 通配全量）" />
        </el-form-item>
        <el-form-item label="密级上限">
          <el-select v-model="rowForm.secLevel" clearable placeholder="不限" style="width: 100%">
            <el-option label="PUBLIC" value="PUBLIC" />
            <el-option label="INTERNAL" value="INTERNAL" />
            <el-option label="SECRET" value="SECRET" />
          </el-select>
        </el-form-item>
        <el-form-item label="动作">
          <el-radio-group v-model="rowForm.action">
            <el-radio label="PASS">PASS（允许）</el-radio>
            <el-radio label="DENY">DENY（拒绝）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="rowForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rowVisible = false">取消</el-button>
        <el-button type="primary" @click="onSaveRow">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 版本历史抽屉 -->
    <el-drawer v-model="versionVisible" :title="`版本历史 — ${versionRuleId}`" size="50%">
      <el-table :data="versions" border stripe size="small">
        <el-table-column prop="ruleVer" label="版本" width="70" align="center" />
        <el-table-column prop="snapshotJson" label="快照" min-width="300" show-overflow-tooltip />
        <el-table-column prop="effDate" label="生效时间" width="160" />
        <el-table-column prop="createBy" label="操作人" width="110" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 14.4.1 行级权限（拆分自原权限控制操作台）：规则 CRUD / 审批流转 / 版本快照 / 生效影响预览。
 * 三页逻辑互不共享，全部留在页内（design D2：单 tab 独有逻辑不强抽）。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getRowPermsApi, createRowPermApi, updateRowPermApi, submitRowPermApi,
  disableRowPermApi, getRowPermVersionsApi, impactPreviewApi
} from '@/api/bi/perm-control'

const rowFilter = ref({ userName: '', roleCode: '' })
const rowRules = ref([])
const rowVisible = ref(false)
const rowForm = ref({})
const versionVisible = ref(false)
const versionRuleId = ref('')
const versions = ref([])
const previewUser = ref('')
const preview = ref({})

async function loadRows() {
  const params = {}
  if (rowFilter.value.userName) params.userName = rowFilter.value.userName
  if (rowFilter.value.roleCode) params.roleCode = rowFilter.value.roleCode
  const res = await getRowPermsApi(params)
  rowRules.value = res.data || []
}

function openRowEdit(row) {
  rowForm.value = row ? {
    id: row.id,
    subjectType: row.userName ? 'user' : 'role',
    subject: row.userName || row.roleCode || '',
    scopeExpr: row.scopeExpr || 'SELF',
    orgId: row.orgId || '',
    scopeList: row.scopeList || '',
    entityId: row.entityId || '',
    secLevel: row.secLevel || '',
    action: row.action || 'PASS',
    remark: row.remark || ''
  } : {
    id: null, subjectType: 'user', subject: '', scopeExpr: 'SELF',
    orgId: '', scopeList: '', entityId: '', secLevel: '', action: 'PASS', remark: ''
  }
  rowVisible.value = true
}

async function onSaveRow() {
  const f = rowForm.value
  const payload = {
    scopeExpr: f.scopeExpr,
    action: f.action,
    secLevel: f.secLevel || null,
    remark: f.remark
  }
  if (f.subjectType === 'user') payload.userName = f.subject
  else payload.roleCode = f.subject
  if (f.scopeExpr === 'SELF_SUBTREE') payload.orgId = f.orgId
  if (f.scopeExpr === 'LIST') payload.scopeList = f.scopeList
  if (f.scopeExpr === 'SELF') payload.entityId = f.entityId
  try {
    if (f.id) await updateRowPermApi(f.id, payload)
    else await createRowPermApi(payload)
    ElMessage.success('已保存草稿')
    rowVisible.value = false
    loadRows()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  }
}

async function onSubmitRow(row) {
  try {
    await submitRowPermApi(row.id)
    ElMessage.success('已提交审批（数据治理专员单签）')
    loadRows()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  }
}

async function onDisableRow(row) {
  await ElMessageBox.confirm('停用后该规则不再参与行级计算（生成停用快照）？', '停用确认', { type: 'warning' })
  try {
    await disableRowPermApi(row.id)
    ElMessage.success('已停用')
    loadRows()
  } catch (e) {
    ElMessage.error(e?.message || '停用失败')
  }
}

async function showVersions(row) {
  versionRuleId.value = row.id
  const res = await getRowPermVersionsApi(row.id)
  versions.value = res.data || []
  versionVisible.value = true
}

async function onPreview() {
  if (!previewUser.value) {
    ElMessage.warning('请输入用户名')
    return
  }
  const res = await impactPreviewApi(previewUser.value)
  preview.value = res.data || {}
}

const sourceRows = computed(() => Object.entries(preview.value.sources || {})
  .map(([entity, source]) => ({ entity, source })))

onMounted(() => {
  loadRows()
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
