<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="本地合规（17.5）：字段扩展、格式校验、字段加载"
      description="引擎+复用既有列（决策5）：包定义/正则规则集中配置禁硬编码（BR-5.7-13）；字段挂 TAXPAYER_NO 等既有列；注册地→包映射（17.5.3）。" />
    <el-tabs v-model="tab">
      <!-- 17.5.1 字段扩展（包定义） -->
      <el-tab-pane label="字段扩展" name="pack">
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
      </el-tab-pane>

      <!-- 17.5.2 格式校验 -->
      <el-tab-pane label="格式校验" name="validate">
        <el-form inline size="small">
          <el-form-item label="注册地">
            <el-select v-model="vv.regPlace" style="width:120px">
              <el-option label="JP 日本" value="JP" /><el-option label="CN 中国" value="CN" />
              <el-option label="EU 欧盟" value="EU" />
            </el-select>
          </el-form-item>
          <el-form-item label="登录番号/税号">
            <el-input v-model="vv.invoiceRegNo" placeholder="T1234567890123" style="width:220px" />
          </el-form-item>
          <el-button type="primary" size="small" @click="doValidate">校验（C-5.7-09）</el-button>
        </el-form>
        <el-alert v-if="vResult" :type="vResult.pass ? 'success' : 'error'" :closable="false"
          :title="vResult.pass
            ? `校验通过（包字段 ${vResult.packCount} 个）`
            : `阻断：${(vResult.errors || []).map(e => e.hint).join('；')}`" />
        <el-alert type="info" :closable="false" style="margin-top:10px"
          title="S-5.7-06：日本登录番号格式 T+13 位数字（T\\d{13}）；校验失败 L1 阻断保存与过账，逐字段提示（C-5.7-09）" />
      </el-tab-pane>

      <!-- 17.5.3 字段加载 -->
      <el-tab-pane label="字段加载" name="load">
        <el-form inline size="small">
          <el-form-item label="法人主体注册地">
            <el-select v-model="ld.regPlace" style="width:140px" @change="doLoad">
              <el-option label="JP（日本）" value="JP" /><el-option label="CN（中国）" value="CN" />
              <el-option label="EU（欧盟）" value="EU" /><el-option label="US（美国，无包）" value="US" />
            </el-select>
          </el-form-item>
          <el-button type="primary" size="small" @click="doLoad">加载字段清单</el-button>
        </el-form>
        <el-table :data="ld.fields" border size="small">
          <el-table-column prop="packCode" label="字段包" width="140" />
          <el-table-column prop="fieldName" label="字段" width="150" />
          <el-table-column prop="columnName" label="挂载既有列" width="150" />
          <el-table-column prop="formatRegex" label="格式规则" min-width="200" show-overflow-tooltip />
          <el-table-column prop="errorHint" label="提示" min-width="220" show-overflow-tooltip />
        </el-table>
        <el-alert v-if="ld.regPlace === 'US'" type="success" :closable="false" style="margin-top:10px"
          title="US 无合规字段包 → 空清单，界面不出现冗余字段（17.5.3 / S-5.7-06）" />
        <el-alert v-else type="info" :closable="false" style="margin-top:10px"
          title="按注册地加载：无关法人主体界面 MUST NOT 出现冗余字段（spec i18n-localization）" />
      </el-tab-pane>
    </el-tabs>

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
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getComplianceApi, saveComplianceApi, loadComplianceApi,
  validateComplianceApi } from '@/api/i18n/i18n'

const route = useRoute()
const tab = ref(route.meta.tab || 'pack')

const pq = ref({ packCode: 'JP_INVOICE' })
const fields = ref([])
const saveVisible = ref(false)
const sf = ref({})

const vv = ref({ regPlace: 'JP', invoiceRegNo: '' })
const vResult = ref(null)

const ld = ref({ regPlace: 'JP', fields: [] })

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

async function doValidate() {
  try {
    const r = await validateComplianceApi(vv.value.regPlace, { invoiceRegNo: vv.value.invoiceRegNo })
    vResult.value = r.data
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '校验失败')
  }
}

async function doLoad() {
  try {
    const r = await loadComplianceApi(ld.value.regPlace)
    ld.value.fields = r.data || []
  } catch { ld.value.fields = [] }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
