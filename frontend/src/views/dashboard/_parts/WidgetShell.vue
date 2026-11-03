<template>
  <el-card
    ref="rootEl"
    shadow="hover"
    class="widget-shell"
    :body-style="{ padding: '12px', height: '100%', boxSizing: 'border-box' }"
  >
    <div class="ws-header">
      <span class="ws-title">
        {{ title }}
        <span v-if="badge !== null && badge !== undefined" class="ws-badge">{{ badge }}</span>
      </span>
      <span class="ws-actions">
        <el-tooltip content="刷新" placement="top">
          <el-button link size="small" :disabled="loading" @click="onRefresh">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </el-tooltip>
        <el-popconfirm title="从工作台移除该组件？" width="200" @confirm="onRemoveConfirm">
          <template #reference>
            <el-button link size="small" type="danger">
              <el-icon><Close /></el-icon>
            </el-button>
          </template>
        </el-popconfirm>
      </span>
    </div>

    <!-- 错误态：单卡失败不传播（spec「单卡失败不拖垮画布」），卡片内占位 + 重试 -->
    <div v-if="error" class="ws-state ws-error">
      <el-icon><WarningFilled /></el-icon>
      <div class="ws-state-text">{{ error }}</div>
      <el-button size="small" type="primary" plain @click="onRefresh">重试</el-button>
    </div>
    <!-- 加载态 -->
    <div v-else-if="loading" class="ws-state">
      <el-icon class="is-loading"><Loading /></el-icon>
      <div class="ws-state-text">加载中…</div>
    </div>
    <!-- 内容：滚动条按需显隐由全局指令 v-reveal-scrollbar 承担（add-scrollbar-reveal D5） -->
    <div v-else class="ws-body" v-reveal-scrollbar>
      <slot />
    </div>
  </el-card>
</template>

<script setup>
/**
 * 工作台 widget 通用卡壳（add-configurable-workbench，spec「Widget 数据加载与刷新」）：
 * 标题栏 + 手动刷新（1s 防抖，design D7 限频防放大 403 计数）+ 移除（popconfirm）+
 * loading / error（错误占位 + 重试，单卡失败不传播）。
 * 移除经 bubbling CustomEvent('dash-remove', {detail:{id}}) 冒泡到网格容器（零转发链），
 * id 取自最近的 .grid-stack-item[data-widget-id] 祖先。
 * 数据请求在各 widget 组件内完成，通过 props 回传 loading/error。
 */
import { ref } from 'vue'
import { Refresh, Close, Loading, WarningFilled } from '@element-plus/icons-vue'

defineProps({
  title: { type: String, required: true },
  loading: { type: Boolean, default: false },
  /** 错误文案；非空即渲染错误态（如「无权限」「加载失败」） */
  error: { type: String, default: '' },
  /** 数字徽标（如未读数/待办数）；null 不显示 */
  badge: { type: [Number, String], default: null },
})

const emit = defineEmits(['refresh'])
const rootEl = ref(null)

let lastAt = 0
function onRefresh() {
  const now = Date.now()
  if (now - lastAt < 1000) return // 1s 防抖
  lastAt = now
  emit('refresh')
}

function onRemoveConfirm() {
  const host = rootEl.value?.$el || rootEl.value // el-card 组件实例取 $el
  const itemEl = host?.closest?.('.grid-stack-item')
  const id = itemEl?.dataset?.widgetId
  if (!id) return
  itemEl.dispatchEvent(new CustomEvent('dash-remove', { bubbles: true, detail: { id } }))
}
</script>

<style scoped>
.widget-shell { height: 100%; }
.widget-shell :deep(.el-card__body) { display: flex; flex-direction: column; height: 100%; }
.ws-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 8px; padding-bottom: 8px; border-bottom: 1px solid var(--el-border-color-lighter);
}
.ws-title { font-size: 14px; font-weight: 600; color: var(--el-text-color-primary); }
.ws-badge {
  display: inline-block; min-width: 18px; padding: 0 5px; margin-left: 6px;
  border-radius: 9px; background: var(--el-color-danger); color: #fff;
  font-size: 11px; line-height: 18px; text-align: center;
}
.ws-actions { display: flex; gap: 2px; }
.ws-body { flex: 1; min-height: 0; overflow: auto; }
/* 滚动条按需显隐的样式在全局 styles/reveal-scrollbar.css（指令 data 属性选择器） */
.ws-state {
  flex: 1; min-height: 90px;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8px; color: var(--el-text-color-secondary); font-size: 13px;
}
.ws-error { color: var(--el-color-danger); }
.ws-state .el-icon { font-size: 22px; }
.ws-state-text { font-size: 13px; }
</style>
