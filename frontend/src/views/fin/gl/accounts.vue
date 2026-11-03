<template>
  <div class="page gl-page">
    <!-- ================= 8.1.1 科目管理 ================= -->
    <el-form inline>
      <el-input v-model="accQuery.keyword" clearable placeholder="编码/名称" style="width:180px" />
      <el-select v-model="accQuery.status" clearable placeholder="状态" style="width:120px">
        <el-option label="启用" value="ACTIVE" />
        <el-option label="停用" value="DISABLED" />
      </el-select>
      <el-button type="primary" @click="loadAccounts">查询</el-button>
      <el-button type="success" @click="accCreateVisible = true">新增科目</el-button>
    </el-form>
    <el-table :data="accRows" border size="small">
      <el-table-column prop="accountCode" label="编码" width="110" />
      <el-table-column prop="accountName" label="名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="direction" label="方向" width="70">
        <template #default="{ row }">{{ row.direction === 'DR' ? '借' : '贷' }}</template>
      </el-table-column>
      <el-table-column prop="category" label="类别" width="90" />
      <el-table-column prop="auxSet" label="辅助核算" min-width="150">
        <template #default="{ row }">{{ row.auxSet || '—' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
                     @click="toggleAccount(row)">
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
        <AppPagination
      :total="accTotal"
      v-model:page-size="accQuery.size"
      :current-page="accQuery.current"
      @current-change="onAccPage"
      style="margin-top:10px"
    />

    <!-- ============ 新增科目弹窗 ============ -->
    <el-dialog v-model="accCreateVisible" title="新增科目" width="480px">
      <el-form :model="accForm" label-width="90px">
        <el-form-item label="编码"><el-input v-model="accForm.accountCode" placeholder="如 660202" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="accForm.accountName" /></el-form-item>
        <el-form-item label="方向">
          <el-radio-group v-model="accForm.direction">
            <el-radio value="DR">借</el-radio>
            <el-radio value="CR">贷</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="accForm.category" style="width:100%">
            <el-option label="资产" value="ASSET" /><el-option label="负债" value="LIAB" />
            <el-option label="权益" value="EQUITY" /><el-option label="成本" value="COST" />
            <el-option label="损益" value="PROFIT" /><el-option label="现金流" value="CASH" />
          </el-select>
        </el-form-item>
        <el-form-item label="辅助核算">
          <el-select v-model="accAuxList" multiple style="width:100%" placeholder="该科目要求的辅助维度">
            <el-option label="客户 CUST" value="CUST" /><el-option label="供应商 SUPP" value="SUPP" />
            <el-option label="项目 PROJ" value="PROJ" /><el-option label="员工 EMP" value="EMP" />
            <el-option label="部门 DEPT" value="DEPT" /><el-option label="利润中心 PROFIT" value="PROFIT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="accCreateVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreateAccount">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 8.1.1 科目管理（拆分自原总账多 tab 页，design D1）。
 * 科目分页/ACTIVE 下拉为共享状态，见 useGl.js（凭证录入页辅助维度选择复用 accOptions）。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { accountCreate, accountSetStatus } from '@/api/fin/gl'
import { useGl } from './useGl'

const { accQuery, accRows, accTotal, loadAccounts } = useGl()

const accCreateVisible = ref(false)
const accAuxList = ref([])
const accForm = reactive({ accountCode: '', accountName: '', direction: 'DR', category: 'ASSET' })

const onAccPage = (p) => { accQuery.current = p; loadAccounts() }
async function doCreateAccount() {
  await accountCreate({ ...accForm, auxSet: accAuxList.value.join(',') || null })
  ElMessage.success('科目已创建')
  accCreateVisible.value = false
  loadAccounts()
}
async function toggleAccount(row) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  await accountSetStatus(row.accountCode, next)
  ElMessage.success(`已${next === 'ACTIVE' ? '启用' : '停用'}`)
  loadAccounts()
}

onMounted(() => {
  loadAccounts()
})
</script>
