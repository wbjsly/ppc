<template>
  <div class="i18n-page">
    <PageInfo title="界面渲染：动态切换、缺失回退" description="BR-5.7-04 切换 500ms 内无需重启；BR-5.7-05 缺失键回退 zh-CN+视觉标记+批量埋点；BR-5.7-06 加载异常整体回退。" style="margin-bottom:12px" />

    <!-- 17.6.2 缺失回退 -->
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
  </div>
</template>

<script setup>
/**
 * 17.6.2 缺失回退（拆分自原界面渲染页）：缺失键统计 / 回退渲染演示。
 * 当前语种、键值数据与源语映射共享状态见 useRender.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { computed, onMounted } from 'vue'
import { useRender } from './useRender'

const { keys, missingCount, sourceMap, loadKeys } = useRender()

const fallbackDemo = computed(() => keys.value.filter(k => !k.targetText).slice(0, 10).map(k => ({
  key: k.keyName, priority: k.priority,
  note: '运行时按 ja-JP 未取到译文 → 回退 zh-CN 渲染 + i18n.missing 计数'
})))

onMounted(loadKeys)
</script>

<style scoped>
.i18n-page { padding: 12px; }
.stat-label { font-size: 12px; color: #909399; }
.stat-num { font-size: 22px; font-weight: bold; color: #303133; margin-top: 4px; }
.stat-num.warn { color: #e6a23c; }
.i18n-missing { color: #909399; border-bottom: 1px dashed #b0b0b0; }
</style>
