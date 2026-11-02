<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="区域配置（17.2）：日期格式、数字格式、货币格式"
      description="三语 Locale 自动应用（S-5.7-02）；输入格式不符阻断保存（C-5.7-08）；Locale 未配置回退 zh-CN（C-5.7-02）。" />
    <el-tabs v-model="tab">
      <!-- 17.2.1 日期格式 -->
      <el-tab-pane label="日期格式" name="date">
        <el-table :data="rows" border size="small">
          <el-table-column prop="locale" label="Locale" width="100" />
          <el-table-column label="日期模板" width="160">
            <template #default="{ row }"><code>{{ row.format.datePattern }}</code></template>
          </el-table-column>
          <el-table-column label="示例（2026-03-18）" width="160">
            <template #default="{ row }"><b>{{ dateSample(row.format.datePattern) }}</b></template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.configured ? 'success' : 'info'">
                {{ row.configured ? '已配置' : '默认模板' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="edit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4 style="margin-top:14px">输入格式校验（C-5.7-08）</h4>
        <el-form inline size="small">
          <el-form-item label="Locale">
            <el-select v-model="vf.locale" style="width:110px">
              <el-option v-for="r in rows" :key="r.locale" :label="r.locale" :value="r.locale" />
            </el-select>
          </el-form-item>
          <el-form-item label="邮编"><el-input v-model="vf.postalCode" placeholder="100-0001" style="width:130px" /></el-form-item>
          <el-form-item label="电话"><el-input v-model="vf.phone" placeholder="+81 3-1234-5678" style="width:170px" /></el-form-item>
          <el-form-item label="日期"><el-input v-model="vf.date" placeholder="2026/03/18" style="width:130px" /></el-form-item>
          <el-button type="primary" size="small" @click="doValidate">校验</el-button>
        </el-form>
        <el-alert v-if="vResult" :type="vResult.pass ? 'success' : 'error'" :closable="false"
          :title="vResult.pass ? '格式校验通过' : `阻断：${(vResult.errors || []).map(e => e.field + ' 期望 ' + e.expected).join('；')}`" />
      </el-tab-pane>

      <!-- 17.2.2 数字格式 -->
      <el-tab-pane label="数字格式" name="number">
        <el-table :data="rows" border size="small">
          <el-table-column prop="locale" label="Locale" width="100" />
          <el-table-column label="数字模板" width="160">
            <template #default="{ row }"><code>{{ row.format.numberPattern }}</code></template>
          </el-table-column>
          <el-table-column label="示例（1284650.123）" min-width="180">
            <template #default="{ row }"><b>{{ numSample(row.format) }}</b></template>
          </el-table-column>
          <el-table-column label="小数位" width="90">
            <template #default="{ row }">{{ row.format.decimalPlaces }}</template>
          </el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:10px"
          title="千分位与小数位按 Locale 模板渲染（S-5.7-02：数量 1,200.000 三位小数）" />
      </el-tab-pane>

      <!-- 17.2.3 货币格式 -->
      <el-tab-pane label="货币格式" name="currency">
        <el-table :data="rows" border size="small">
          <el-table-column prop="locale" label="Locale" width="100" />
          <el-table-column label="货币符号" width="110">
            <template #default="{ row }"><b>{{ row.format.currencySymbol }}</b></template>
          </el-table-column>
          <el-table-column label="小数位（ISO 4217）" width="150">
            <template #default="{ row }">{{ row.format.decimalPlaces }} 位</template>
          </el-table-column>
          <el-table-column label="示例（JPY 1284650）" min-width="180">
            <template #default="{ row }">
              <b>{{ row.format.currencySymbol }}{{ curSample(row.format) }}</b>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:10px"
          title="BR-5.7-09：JPY=0 位小数、CNY/USD=2 位；折算结果按币种主数据小数位四舍五入并保留取整差值" />
      </el-tab-pane>
    </el-tabs>

    <!-- 格式编辑抽屉 -->
    <el-drawer v-model="editVisible" :title="`编辑 ${ef.locale} 格式`" size="420px">
      <el-form label-width="100px" size="small">
        <el-form-item label="日期模板"><el-input v-model="ef.format.datePattern" placeholder="yyyy-MM-dd" /></el-form-item>
        <el-form-item label="数字模板"><el-input v-model="ef.format.numberPattern" placeholder="#,##0.00" /></el-form-item>
        <el-form-item label="货币符号"><el-input v-model="ef.format.currencySymbol" style="width:80px" /></el-form-item>
        <el-form-item label="小数位"><el-input-number v-model="ef.decimals" :min="0" :max="4" /></el-form-item>
        <el-form-item label="邮编正则"><el-input v-model="ef.format.postalRegex" /></el-form-item>
        <el-form-item label="电话正则"><el-input v-model="ef.format.phoneRegex" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible=false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getLocalesApi, saveLocaleApi, validateLocaleApi } from '@/api/i18n/i18n'

const route = useRoute()
const tab = ref(route.meta.tab || 'date')

const rows = ref([])
const editVisible = ref(false)
const ef = ref({ locale: '', format: {}, decimals: 2 })
const vf = ref({ locale: 'ja-JP', postalCode: '', phone: '', date: '' })
const vResult = ref(null)

onMounted(load)

async function load() {
  try {
    const r = await getLocalesApi()
    rows.value = r.data || []
  } catch { rows.value = [] }
}

function dateSample(p) {
  if (!p) return '2026-03-18'
  if (p.includes('MM/dd')) return '03/18/2026'
  if (p.includes('yyyy/MM')) return '2026/03/18'
  return '2026-03-18'
}
function numSample(f) {
  const n = 1284650.123
  const d = parseInt(f.decimalPlaces || '2', 10)
  return n.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d })
}
function curSample(f) {
  const d = parseInt(f.decimalPlaces || '2', 10)
  return (1284650).toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d })
}

function edit(row) {
  ef.value = {
    locale: row.locale,
    format: { ...row.format },
    decimals: parseInt(row.format.decimalPlaces || '2', 10)
  }
  editVisible.value = true
}
async function save() {
  const fmt = { ...ef.value.format, decimalPlaces: String(ef.value.decimals) }
  await saveLocaleApi({ locale: ef.value.locale, format: fmt })
  ElMessage.success('格式已保存')
  editVisible.value = false
  load()
}
async function doValidate() {
  const fields = {}
  if (vf.value.postalCode) fields.postalCode = vf.value.postalCode
  if (vf.value.phone) fields.phone = vf.value.phone
  if (vf.value.date) fields.date = vf.value.date
  try {
    const r = await validateLocaleApi(vf.value.locale, fields)
    vResult.value = r.data
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '校验失败')
  }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
