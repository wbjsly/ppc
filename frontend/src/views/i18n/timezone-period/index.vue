<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="时区期间（17.4）：时区换算、期间归属"
      description="UTC 存储+三口径呈现（SOP-5.7-C 步4）；期间按法人主体+本地时间判定（C-5.7-10，校验展示区不改过账链）。" />
    <el-tabs v-model="tab">
      <!-- 17.4.1 时区换算 -->
      <el-tab-pane label="时区换算" name="tz">
        <el-form inline size="small">
          <el-form-item label="UTC 时间">
            <el-date-picker v-model="utcTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"
              placeholder="2026-03-31 14:50" style="width:200px" />
          </el-form-item>
          <el-form-item label="或输入">
            <el-input v-model="utcInput" placeholder="2026-03-31T14:50:00Z" style="width:220px" />
          </el-form-item>
          <el-button type="primary" size="small" @click="convert">换算</el-button>
        </el-form>
        <el-table v-if="tzRows.length" :data="tzRows" border size="small">
          <el-table-column prop="zone" label="时区（IANA）" width="200" />
          <el-table-column prop="label" label="口径" width="160" />
          <el-table-column prop="local" label="本地时间" min-width="200">
            <template #default="{ row }"><b>{{ row.local }}</b></template>
          </el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:10px"
          title="SOP-5.7-C 步1：数据库统一 UTC 存储；单据保留 UTC/业务本地时间/来源时区三字段；时区标识用 IANA（Asia/Tokyo 等）" />
        <el-alert v-if="tzWarn" type="warning" :closable="false" style="margin-top:8px"
          :title="tzWarn" />
      </el-tab-pane>

      <!-- 17.4.2 期间归属 -->
      <el-tab-pane label="期间归属" name="period">
        <el-form inline size="small">
          <el-form-item label="法人主体">
            <el-select v-model="pq.leId" filterable style="width:220px" placeholder="选择法人主体">
              <el-option v-for="le in les" :key="le.id" :label="`${le.leCode} ${le.leName}`" :value="le.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="业务本地时间">
            <el-date-picker v-model="pq.bizTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
              style="width:200px" />
          </el-form-item>
          <el-button type="primary" size="small" @click="queryPeriod">判定期间</el-button>
        </el-form>

        <el-result v-if="periodResult" :icon="periodResult.ok ? 'success' : 'error'"
          :title="periodResult.ok ? `归属期间：${periodResult.periodName}` : periodResult.blockReason">
          <template #sub-title>
            <div v-if="periodResult.ok">
              <p>会计日历：{{ periodResult.calendarType }}（{{ periodResult.calendarNote }}）</p>
              <p>判定口径：法人主体时区 {{ periodResult.tz }} + 业务本地时间 {{ pq.bizTime }}（非操作者时区，C-5.7-10）</p>
            </div>
            <div v-else>
              <p>校验展示区（不改过账链本体）；跳转：财务期间生成/重开（BR-5.7-12）</p>
            </div>
          </template>
        </el-result>

        <h4 style="margin-top:12px">会计日历类型（SOP-5.7-C 步2）</h4>
        <el-table :data="calendars" border size="small">
          <el-table-column prop="type" label="类型" width="140" />
          <el-table-column prop="note" label="说明" min-width="280" />
          <el-table-column prop="example" label="示例" min-width="200" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const route = useRoute()
const tab = ref(route.meta.tab || 'tz')

const utcTime = ref('')
const utcInput = ref('2026-03-31T14:50:00Z')
const tzRows = ref([])
const tzWarn = ref('')

const pq = ref({ leId: '', bizTime: '2026-03-31 23:50:00' })
const les = ref([])
const periodResult = ref(null)

const calendars = [
  { type: '自然年制', note: '1 月 1 日 ~ 12 月 31 日（中国/美国默认）', example: '2026-01-01 ~ 2026-12-31' },
  { type: '4 月制', note: '4 月 1 日 ~ 次年 3 月 31 日（日本子公司 LE-005）', example: '2025-04-01 ~ 2026-03-31 = FY2025 第 12 期' },
  { type: '7 月制', note: '7 月 1 日 ~ 次年 6 月 30 日', example: '2025-07-01 ~ 2026-06-30' },
  { type: '52-53 周制', note: '按周归属，期间跨度不一致时 L4 提示财务确认分摊', example: '52/53 周年度' }
]

onMounted(loadLes)

async function loadLes() {
  try {
    const r = await request.get('/mdm/legal-entities', { params: { current: 1, size: 50 } })
    les.value = r.data?.records || r.data || []
  } catch { les.value = [] }
}

function convert() {
  tzWarn.value = ''
  const raw = utcInput.value || utcTime.value
  if (!raw) { ElMessage.warning('输入 UTC 时间'); return }
  let d
  try {
    d = new Date(raw)
    if (Number.isNaN(d.getTime())) throw new Error('invalid')
  } catch {
    ElMessage.error('时间格式不正确（如 2026-03-31T14:50:00Z）')
    return
  }
  if (!raw.includes('Z') && !raw.includes('+')) {
    tzWarn.value = '时间戳缺时区标识 → 按法人主体默认时区补齐并留痕（BR-5.7-11，L4）'
  }
  const zones = [
    { zone: 'UTC', label: 'UTC 口径', off: 0 },
    { zone: 'Asia/Shanghai', label: '上海总部（UTC+8）', off: 8 },
    { zone: 'Asia/Tokyo', label: '东京（UTC+9）', off: 9 },
    { zone: 'America/New_York', label: '纽约（UTC-4 夏令时近似）', off: -4 }
  ]
  tzRows.value = zones.map(z => {
    const t = new Date(d.getTime() + z.off * 3600000)
    const p = n => String(n).padStart(2, '0')
    return {
      zone: z.zone, label: z.label,
      local: `${t.getUTCFullYear()}-${p(t.getUTCMonth() + 1)}-${p(t.getUTCDate())} ${p(t.getUTCHours())}:${p(t.getUTCMinutes())}:${p(t.getUTCSeconds())}`
    }
  })
}

async function queryPeriod() {
  periodResult.value = null
  if (!pq.value.leId) { ElMessage.warning('选择法人主体'); return }
  try {
    const r = await request.get('/fin/periods/judge', {
      params: { legalEntityId: pq.value.leId, bizTime: pq.value.bizTime }
    })
    periodResult.value = r.data
  } catch (e) {
    // 端点差异：按未生成期间处理（展示区 L1 阻断语义，BR-5.7-12）
    const le = les.value.find(x => x.id === pq.value.leId) || {}
    periodResult.value = {
      ok: false,
      blockReason: '会计期间未配置/未生成（BR-5.7-12，L1 阻断展示）',
      calendarType: le.calendarType || '自然年制',
      tz: le.timezone || 'Asia/Shanghai'
    }
  }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
