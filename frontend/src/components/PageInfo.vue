<template>
  <!-- 空文案守卫：title 与 description 均缺 → 整体不渲染（spec：无说明页面不出现 icon） -->
  <div v-if="hasContent" class="page-info">
    <el-tooltip placement="bottom-end" :show-after="120">
      <template #content>
        <div class="pi-tip">
          <div v-if="title" class="pi-tip__title">{{ title }}</div>
          <div v-if="preview" class="pi-tip__body">{{ preview }}</div>
          <div class="pi-tip__hint">点击查看详情</div>
        </div>
      </template>
      <!-- @click 只绑 icon 本身：el-dialog 默认不 teleport（挂在组件原位），
           若绑在根 div 上，弹框内点击冒泡回根会再次 open()——"关了又被重开"的根因 -->
      <span class="page-info__icon" @click.stop="open">
        <el-icon :size="14"><InfoFilled /></el-icon>
      </span>
    </el-tooltip>

    <!-- 全文弹框：任意处关闭 = 原生 DOM 监听挂到 .el-dialog 元素（design D4）——
         header/正文/留白/关闭钮 的点击都会冒泡到 .el-dialog；遮罩点击由 close-on-click-modal。
         append-to-body 与根 div 隔离（防重入第二道保险） -->
    <el-dialog
      v-model="visible"
      width="560px"
      align-center
      class="page-info-dialog"
      :class="{ 'page-info-dialog--single': !hasDesc }"
      :close-on-click-modal="true"
    >
      <template #header>
        <div v-if="hasDesc" class="pi-header">{{ title }}</div>
      </template>
      <div class="pi-body">
        <div class="pi-full">{{ description || title }}</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 页面说明信息右上角入口（add-page-info-icon，spec page-info-display）：
 * hover 速览（限宽截断，design D3：速览/全文同源 props）→ 点击弹框全文 →
 * 弹框任意处关闭（design D4：拖选文本会顺带关框，需求明示接受）。
 * 单段式（无 description）：正文=title 全文，隐藏弹框标题行避免重复。
 */
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'

const props = defineProps({
  title: { type: String, default: '' },
  description: { type: String, default: '' },
})

const visible = ref(false)

const hasContent = computed(() => !!(props.title || props.description))
const hasDesc = computed(() => !!props.description)
/** 速览正文：有 description 用其截断；单段式用 title 全文（本身即一句提示） */
const preview = computed(() => {
  const text = props.description || props.title
  if (!text) return ''
  return text.length > 100 ? `${text.slice(0, 100)}…` : text
})

// ---- 弹框任意处关闭：open 后在 .el-dialog 原生元素直挂 click 监听 ----
// header/正文/留白/× 均冒泡至 .el-dialog；遮罩由 close-on-click-modal 负责。
// once + 关闭时兜底摘除，避免重复挂载。
let detach = null
function hideDialog() { visible.value = false }
watch(visible, async v => {
  if (v) {
    await nextTick()
    // append-to-body 后 custom class 在 .el-dialog 元素自身（在 body 层）
    const dlg = document.querySelector('.el-dialog.page-info-dialog')
      || document.querySelector('.el-dialog')
    if (!dlg) return
    const handler = () => hideDialog()
    dlg.addEventListener('click', handler, { once: true })
    detach = () => dlg.removeEventListener('click', handler)
  } else if (detach) {
    detach(); detach = null
  }
})
onBeforeUnmount(() => { if (detach) { detach(); detach = null } })

function open() { visible.value = true }
</script>

<style scoped>
.page-info {
  position: sticky;
  top: 0;
  z-index: 6;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  height: 26px;
}
.page-info__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  cursor: pointer;
  transition: all 0.15s;
}
.page-info__icon:hover {
  background: var(--el-color-primary-light-7);
  color: var(--el-color-primary);
}
/* 速览浮层（320px 限宽换行，spec：长文案不撑破） */
.pi-tip { max-width: 320px; }
.pi-tip__title { font-weight: 600; margin-bottom: 4px; }
.pi-tip__body { line-height: 1.7; word-break: break-word; }
.pi-tip__hint { margin-top: 6px; font-size: 11px; opacity: 0.75; }
/* 全文弹框（插槽内容：带本组件 data-v，scoped 生效） */
.pi-header { cursor: pointer; padding: 16px 44px 6px 20px; } /* 右侧 44px 避开 ×按钮 */
.pi-body { cursor: pointer; padding: 4px 20px 20px; }
.pi-full { line-height: 1.9; font-size: 14px; white-space: pre-wrap; word-break: break-word; }
</style>

<style>
/* 容器级规则必须非 scoped：.page-info-dialog 在 el-dialog（EP 根）上，
   append-to-body/teleport 后该元素不带本组件的 data-v，scoped 选择器匹配不到 */
.page-info-dialog .el-dialog__header { padding: 0; }
.page-info-dialog .el-dialog__body { padding: 0; }
.page-info-dialog .el-dialog__headerbtn { z-index: 2; }
.page-info-dialog--single .el-dialog__header { display: none; }
</style>
