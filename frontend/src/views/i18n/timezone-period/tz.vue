<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="时区期间（17.4）：时区换算、期间归属"
      description="UTC 存储+三口径呈现（SOP-5.7-C 步4）；期间按法人主体+本地时间判定（C-5.7-10，校验展示区不改过账链）。" />

    <!-- 17.4.1 时区换算 -->
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
  </div>
</template>

<script setup>
/**
 * 17.4.1 时区换算（拆分自原时区期间页）：UTC 时间三口径换算。
 * 与期间归属页无共享状态（design D2：单页逻辑留页内）。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const utcTime = ref('')
const utcInput = ref('2026-03-31T14:50:00Z')
const tzRows = ref([])
const tzWarn = ref('')

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
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
