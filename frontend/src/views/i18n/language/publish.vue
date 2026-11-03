<template>
  <div class="i18n-page">
    <PageInfo title="语言管理（17.1）：键值维护、缺口报告、版本发布" description="后端表为源（决策2）；占位符不一致拒绝导入（BR-5.7-03）；预检 P0/P1=100%+总体≥99% 阻断发布（BR-5.7-01/02）。" style="margin-bottom:12px" />

    <!-- 17.1.3 版本发布（SOP-5.7-B 六步） -->
    <el-alert type="warning" :closable="false" style="margin-bottom:10px"
      title="发布六步：构建 → 预检 → 回退策略 → 审批 → 灰度（10%，异常率>0.1% 自动回退 BR-5.7-07）→ 归档锁定（C-0-05）" />
    <el-form inline size="small">
      <el-form-item label="语种">
        <el-select v-model="pub.locale" style="width:120px">
          <el-option label="en-US" value="en-US" /><el-option label="ja-JP" value="ja-JP" />
        </el-select>
      </el-form-item>
      <el-form-item label="版本号">
        <el-input v-model="pub.packNo" placeholder="如 1.0.0" style="width:110px" />
      </el-form-item>
      <el-form-item label="回退策略" required>
        <el-input v-model="pub.fallback" placeholder='{"locale":"zh-CN","visualMark":true,"track":true}' style="width:300px" />
      </el-form-item>
      <el-button type="primary" size="small" @click="build">构建版本</el-button>
      <el-button size="small" @click="runPrecheck">预检</el-button>
    </el-form>
    <el-alert v-if="precheck" :type="precheck.pass ? 'success' : 'error'" :closable="false" style="margin-bottom:10px"
      :title="precheck.pass
        ? `预检通过：总体 ${precheck.coverage}% ≥ ${precheck.coverageMin}%，P0 ${precheck.p0Coverage}% / P1 ${precheck.p1Coverage}% = 100%`
        : `预检阻断：${precheck.blockReason}（缺口 ${(precheck.gaps || []).length} 条）`" />
    <el-table :data="packs" border size="small">
      <el-table-column prop="packNo" label="版本" width="100" />
      <el-table-column prop="locale" label="语种" width="90" />
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="stTag(row.status)">{{ stText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="canaryPct" label="灰度%" width="80" />
      <el-table-column label="操作" width="300">
        <template #default="{ row }">
          <el-button v-if="row.status==='DRAFT'" size="small" type="primary" @click="approve(row)">审批</el-button>
          <el-button v-if="row.status==='APPROVED'" size="small" @click="canary(row)">灰度</el-button>
          <el-button v-if="row.status==='CANARY'" size="small" @click="canaryCheck(row)">灰度检查</el-button>
          <el-button v-if="row.status!=='ARCHIVED'" size="small" type="warning" @click="archive(row)">归档</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 17.1.3 版本发布（拆分自原语言管理页）：构建版本 / 预检 / 审批 / 灰度 / 归档。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { buildPackApi, precheckApi, approvePackApi, startCanaryApi, checkCanaryApi,
  archivePackApi, getPacksApi } from '@/api/i18n/i18n'

const pub = ref({ locale: 'ja-JP', packNo: '1.0.0', fallback: '{"locale":"zh-CN","visualMark":true,"track":true}' })
const precheck = ref(null)
const packs = ref([])

function stTag(s) {
  return { DRAFT: 'info', PRECHECK: 'warning', REVIEWING: 'warning', APPROVED: 'primary', CANARY: '', ARCHIVED: 'success' }[s] || 'info'
}
function stText(s) {
  return { DRAFT: '草稿', PRECHECK: '预检', REVIEWING: '审核中', APPROVED: '已审批', CANARY: '灰度中', ARCHIVED: '已归档' }[s] || s
}

onMounted(() => { loadPacks() })

async function loadPacks() {
  try {
    const r = await getPacksApi()
    packs.value = r.data || []
  } catch { packs.value = [] }
}
async function build() {
  try {
    await buildPackApi({ locale: pub.value.locale, packNo: pub.value.packNo,
      fallback: pub.value.fallback, remark: '' })
    // 回退策略随版本（写 remark 旁路：直接随构建传）
    ElMessage.success(`版本 ${pub.value.packNo} 已构建（不可变）`)
    loadPacks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '构建失败')
  }
}
async function runPrecheck() {
  const r = await precheckApi(pub.value.locale)
  precheck.value = r.data
}
async function approve(row) {
  try {
    await approvePackApi(row.id)
    ElMessage.success('审批通过（预检强制通过）')
    loadPacks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '审批被阻断（预检不通过 BR-5.7-01/02）')
    runPrecheck()
  }
}
async function canary(row) {
  try {
    await startCanaryApi(row.id, 10)
    ElMessage.success('灰度已启动（10%，回退策略前置校验）')
    loadPacks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '灰度启动失败（回退策略未配置）')
  }
}
async function canaryCheck(row) {
  try {
    const r = await checkCanaryApi(row.id)
    if (r.data.rolledBack) {
      ElMessageBox.alert(r.data.reason, '灰度自动回退（BR-5.7-07）', { type: 'warning' })
    } else {
      ElMessage.success('灰度正常（异常率 ≤0.1%）')
    }
    loadPacks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '检查失败')
  }
}
async function archive(row) {
  await ElMessageBox.confirm(`归档版本 ${row.packNo}？归档后不可修改（C-0-05）`, '提示', { type: 'warning' })
  await archivePackApi(row.id)
  ElMessage.success('已归档')
  loadPacks()
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
