<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="界面渲染（17.6）：动态切换、缺失回退"
      description="BR-5.7-04 切换 500ms 内无需重启；BR-5.7-05 缺失键回退 zh-CN+视觉标记+批量埋点；BR-5.7-06 加载异常整体回退。" />
    <el-tabs v-model="tab">
      <!-- 17.6.1 动态切换 -->
      <el-tab-pane label="动态切换" name="switch">
        <el-form inline size="small">
          <el-form-item label="当前语种">
            <el-select :model-value="currentLocale" style="width:160px" @change="switchLocale">
              <el-option v-for="l in LOCALES" :key="l.value" :label="l.label" :value="l.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="切换耗时">
            <el-tag :type="switchMs <= 500 ? 'success' : 'danger'">{{ switchMs }} ms</el-tag>
          </el-form-item>
        </el-form>
        <el-alert :type="switchMs <= 500 ? 'success' : 'warning'" :closable="false" style="margin-bottom:12px"
          :title="switchMs <= 500
            ? `切换完成 ${switchMs}ms ≤ 500ms（BR-5.7-04）：无需重启/重登，当前页面状态与未提交数据保留`
            : `切换耗时 ${switchMs}ms 超出 500ms 目标（BR-5.7-04）`" />
        <h4>实时文案预览（随语种切换）</h4>
        <el-table :data="previewKeys" border size="small">
          <el-table-column prop="key" label="键名" width="220" />
          <el-table-column label="当前渲染" min-width="240">
            <template #default="{ row }">
              <span :class="{ 'i18n-missing': row.missing }">{{ row.text }}</span>
              <el-tag v-if="row.missing" size="small" type="info" style="margin-left:6px">未本地化</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="zh-CN 源语" min-width="200">
            <template #default="{ row }">{{ sourceMap[row.key] || '—' }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 17.6.2 缺失回退 -->
      <el-tab-pane label="缺失回退" name="fallback">
        <el-row :gutter="12" style="margin-bottom:12px">
          <el-col :span="6"><el-card shadow="never"><div class="stat-label">语言包缺失键</div><div class="stat-num warn">{{ missingCount }}</div></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><div class="stat-label">回退策略</div><div class="stat-num" style="font-size:14px">zh-CN 默认语种</div></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><div class="stat-label">视觉标记</div><div class="stat-num" style="font-size:14px">浅灰虚下划线</div></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><div class="stat-label">埋点通道</div><div class="stat-num" style="font-size:14px">i18n.missing 批量回传</div></el-card></el-col>
        </el-row>
        <el-alert type="warning" :closable="false" style="margin-bottom:10px"
          title="回退演示（S-5.7-05）：切换到 ja-JP 后下方缺口键以 zh-CN 文案渲染并带虚线标记，命中计入缺口台账（30s 批量回传）" />
        <el-table :data="fallbackDemo" border size="small">
          <el-table-column prop="key" label="缺口键（ja-JP 未翻译）" width="260" />
          <el-table-column label="回退渲染（zh-CN）" min-width="240">
            <template #default="{ row }">
              <span class="i18n-missing">{{ sourceMap[row.key] || row.key }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="priority" label="优先级" width="90" />
          <el-table-column prop="note" label="说明" min-width="220" show-overflow-tooltip />
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:10px"
          title="渲染异常（语言包加载失败/解析失败/编码非 UTF-8）→ 整体回退默认包 + i18n.render_error 告警（BR-5.7-06 / C-5.7-04）" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { LOCALES, setLocale, getCurrentLocale, getMissingCount } from '@/i18n'
import { getKeysApi } from '@/api/i18n/i18n'

const route = useRoute()
const tab = ref(route.meta.tab || 'switch')

const currentLocale = ref(getCurrentLocale())
const switchMs = ref(0)
const keys = ref([])
const missingCount = ref(0)

const previewKeys = computed(() => keys.value.slice(0, 12).map(k => {
  const target = k.targetText || ''
  return {
    key: k.keyName,
    text: target || k.sourceText || k.keyName,
    missing: !target
  }
}))
const sourceMap = computed(() => {
  const m = {}
  keys.value.forEach(k => { m[k.keyName] = k.sourceText })
  return m
})
const fallbackDemo = computed(() => keys.value.filter(k => !k.targetText).slice(0, 10).map(k => ({
  key: k.keyName, priority: k.priority,
  note: '运行时按 ja-JP 未取到译文 → 回退 zh-CN 渲染 + i18n.missing 计数'
})))

onMounted(loadKeys)

async function loadKeys() {
  try {
    const r = await getKeysApi({ locale: currentLocale.value === 'zh-CN' ? 'ja-JP' : currentLocale.value, size: 200 })
    keys.value = r.data || []
    missingCount.value = getMissingCount(currentLocale.value)
  } catch { keys.value = [] }
}

async function switchLocale(v) {
  const t0 = performance.now()
  await setLocale(v)
  switchMs.value = Math.round(performance.now() - t0)
  currentLocale.value = v
  ElMessage.success(`已切换 ${v}（${switchMs.value}ms，无需重启）`)
  loadKeys()
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
.stat-label { font-size: 12px; color: #909399; }
.stat-num { font-size: 22px; font-weight: bold; color: #303133; margin-top: 4px; }
.stat-num.warn { color: #e6a23c; }
.i18n-missing { color: #909399; border-bottom: 1px dashed #b0b0b0; }
</style>
