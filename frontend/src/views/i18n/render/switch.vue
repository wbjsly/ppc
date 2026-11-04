<template>
  <div class="i18n-page">
    <PageInfo title="界面渲染：动态切换、缺失回退" description="BR-5.7-04 切换 500ms 内无需重启；BR-5.7-05 缺失键回退 zh-CN+视觉标记+批量埋点；BR-5.7-06 加载异常整体回退。" style="margin-bottom:12px" />

    <!-- 17.6.1 动态切换 -->
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
  </div>
</template>

<script setup>
/**
 * 17.6.1 动态切换（拆分自原界面渲染页）：语种切换耗时 / 实时文案预览。
 * 当前语种、键值数据与源语映射共享状态见 useRender.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { LOCALES, setLocale } from '@/i18n'
import { useRender } from './useRender'

const { currentLocale, keys, sourceMap, loadKeys } = useRender()

const switchMs = ref(0)

const previewKeys = computed(() => keys.value.slice(0, 12).map(k => {
  const target = k.targetText || ''
  return {
    key: k.keyName,
    text: target || k.sourceText || k.keyName,
    missing: !target
  }
}))

onMounted(loadKeys)

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
.i18n-missing { color: #909399; border-bottom: 1px dashed #b0b0b0; }
</style>
