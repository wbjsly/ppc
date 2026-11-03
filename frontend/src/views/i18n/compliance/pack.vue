<template>
  <div class="i18n-page">
    <PageInfo title="本地合规（17.5）：字段扩展、格式校验、字段加载" description="引擎+复用既有列（决策5）：包定义/正则规则集中配置禁硬编码（BR-5.7-13）；字段挂 TAXPAYER_NO 等既有列；注册地→包映射（17.5.3）。" style="margin-bottom:12px" />

    <!-- 17.5.1 字段扩展（包定义） -->
    <el-form inline size="small">
      <el-form-item label="包编码">
        <el-select v-model="pq.packCode" clearable style="width:160px" @change="loadFields">
          <el-option label="JP_INVOICE（日本インボイス）" value="JP_INVOICE" />
          <el-option label="CN_TAX（中国税号）" value="CN_TAX" />
          <el-option label="EU_VAT（欧盟 VAT）" value="EU_VAT" />
        </el-select>
      </el-form-item>
      <el-button type="primary" size="small" @click="openSave()">新增字段</el-button>
    </el-form>
    <el-table :data="fields" border size="small">
      <el-table-column prop="packCode" label="包" width="130" />
      <el-table-column prop="regPlace" label="注册地" width="90" />
      <el-table-column prop="fieldName" label="字段名" width="140" />
      <el-table-column prop="columnName" label="既有列（复用）" width="150" />
      <el-table-column prop="formatRegex" label="格式正则" min-width="200" show-overflow-tooltip />
      <el-table-column prop="required" label="必填" width="70" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.required === '1' ? 'danger' : 'info'">{{ row.required === '1' ? '是' : '否' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="errorHint" label="错误提示" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openSave(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 字段编辑抽屉 -->
    <el-drawer v-model="saveVisible" :title="sf.id ? '编辑字段' : '新增字段包定义'" size="440px">
      <el-form label-width="110px" size="small">
        <el-form-item label="包编码" required>
          <el-select v-model="sf.packCode" style="width:100%">
            <el-option label="JP_INVOICE" value="JP_INVOICE" />
            <el-option label="CN_TAX" value="CN_TAX" />
            <el-option label="EU_VAT" value="EU_VAT" />
          </el-select>
        </el-form-item>
        <el-form-item label="注册地" required>
          <el-select v-model="sf.regPlace" style="width:100%">
            <el-option label="JP" value="JP" /><el-option label="CN" value="CN" /><el-option label="EU" value="EU" />
          </el-select>
        </el-form-item>
        <el-form-item label="字段名" required><el-input v-model="sf.fieldName" placeholder="invoiceRegNo" /></el-form-item>
        <el-form-item label="既有列" required><el-input v-model="sf.columnName" placeholder="TAXPAYER_NO（复用不新建）" /></el-form-item>
        <el-form-item label="格式正则"><el-input v-model="sf.formatRegex" placeholder="^T\\d{13}$" /></el-form-item>
        <el-form-item label="必填"><el-switch v-model="sf.requiredBool" /></el-form-item>
        <el-form-item label="错误提示"><el-input v-model="sf.errorHint" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveVisible=false">取消</el-button>
        <el-button type="primary" @click="save">保存（BR-5.7-13 集中配置）</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 17.5.1 字段扩展（拆分自原本地合规页）：字段包定义查询 / 新增编辑抽屉。
 * 与格式校验、字段加载页无共享状态（design D2：单页逻辑留页内）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getComplianceApi, saveComplianceApi } from '@/api/i18n/i18n'

const pq = ref({ packCode: 'JP_INVOICE' })
const fields = ref([])
const saveVisible = ref(false)
const sf = ref({})

onMounted(loadFields)

async function loadFields() {
  try {
    const r = await getComplianceApi({ packCode: pq.value.packCode || undefined })
    fields.value = r.data || []
  } catch { fields.value = [] }
}
function openSave(row) {
  sf.value = row
    ? { ...row, requiredBool: row.required === '1' }
    : { packCode: 'JP_INVOICE', regPlace: 'JP', fieldName: '', columnName: 'TAXPAYER_NO',
        formatRegex: '', errorHint: '', requiredBool: true }
  saveVisible.value = true
}
async function save() {
  try {
    await saveComplianceApi({
      packCode: sf.value.packCode, regPlace: sf.value.regPlace,
      fieldName: sf.value.fieldName, columnName: sf.value.columnName,
      formatRegex: sf.value.formatRegex,
      required: sf.value.requiredBool ? '1' : '0',
      errorHint: sf.value.errorHint
    })
    ElMessage.success('字段包定义已保存（规则集中配置）')
    saveVisible.value = false
    loadFields()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败（正则不合法）')
  }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
