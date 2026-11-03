<template>
  <div class="ap-pagination">
    <span class="ap-total">共 {{ total }} 条</span>
    <el-select
      class="ap-sizes"
      :model-value="effectiveSize"
      :disabled="disabled"
      popper-class="ap-sizes-popper"
      @change="onSizeChange"
    >
      <el-option v-for="s in pageSizes" :key="s" :label="`${s} 条/页`" :value="s" />
    </el-select>
    <button
      type="button"
      class="ap-btn"
      :disabled="disabled || isFirstPage(effectiveCurrent)"
      aria-label="跳转到第一页"
      @click="goFirst"
    >«</button>
    <el-pagination
      :current-page="effectiveCurrent"
      :page-size="effectiveSize"
      :total="total"
      :disabled="disabled"
      :small="small"
      :background="background"
      layout="prev, pager, next"
      @current-change="onPagerChange"
    />
    <button
      type="button"
      class="ap-btn"
      :disabled="disabled || isLastPage(effectiveCurrent, total, effectiveSize)"
      aria-label="跳转到最后一页"
      @click="goLast"
    >»</button>
  </div>
</template>

<script setup>
/**
 * AppPagination — 全站统一分页（add-unified-pagination，design D2/D3/D4）：
 * 根 flex 右对齐；「共 N 条」+ 每页条数下拉（10/20/50/100/200 默认 20，自绘）
 * + «» 首末页（EP 2.6.1 无 first/last 能力，自绘）+ 内层 el-pagination 只承载
 * prev/pager/next 页码簇（EP 行内顺序固定，sizes 进内部行会挡住 « 的插入位）。
 *
 * 受控/非受控（EP 同款模式）：父级绑了 v-model:current-page / v-model:page-size
 * 则受控回写，否则组件内部自持（未绑定站点也能完整交互）。
 *
 * 尺寸切换事件序列由 pagination-logic.sizeChangeEventPlan 决定（D3 关键）：
 * 先回写 size → 强制回第 1 页 → 补发 current-change(1)——页面既有 @current-change
 * 加载器原样复用，第 1 页切尺寸也会重载。
 */
import { computed, ref, getCurrentInstance } from 'vue'
import {
  DEFAULT_PAGE_SIZES,
  DEFAULT_PAGE_SIZE,
  lastPageOf,
  isFirstPage,
  isLastPage,
  sizeChangeEventPlan,
  jumpEventPlan,
} from './pagination-logic.js'

const props = defineProps({
  total: { type: Number, default: 0 },
  pageSize: { type: Number, default: DEFAULT_PAGE_SIZE },
  currentPage: { type: Number, default: 1 },
  pageSizes: { type: Array, default: () => DEFAULT_PAGE_SIZES },
  disabled: { type: Boolean, default: false },
  /** 存量页面的 EP 视觉开关，原样转发内层（迁移保留不丢失意图） */
  small: { type: Boolean, default: false },
  background: { type: Boolean, default: false },
})
const emit = defineEmits([
  'update:current-page',
  'update:page-size',
  'current-change',
  'size-change',
])

// 受控判定（EP 真实模型）：vnode.props 是否【传入】了该 prop 决定取值侧（受控/自持）；
// 是否有 updater 监听决定回写侧是否外发 update:*（两者分离——存在
// `:current-page` 只配事件处理器由父级自行回写、或完全不绑靠内部态的存量站点）。
const vnodeProps = getCurrentInstance()?.vnode.props || {}
const isCurrentPassed = 'currentPage' in vnodeProps || 'current-page' in vnodeProps
const hasCurrentUpdater =
  'onUpdate:currentPage' in vnodeProps || 'onUpdate:current-page' in vnodeProps
const isSizePassed = 'pageSize' in vnodeProps || 'page-size' in vnodeProps
const hasSizeUpdater =
  'onUpdate:PageSize' in vnodeProps ||
  'onUpdate:pageSize' in vnodeProps ||
  'onUpdate:page-size' in vnodeProps

const innerCurrent = ref(props.currentPage)
const innerSize = ref(props.pageSize)

const effectiveCurrent = computed(() =>
  isCurrentPassed ? props.currentPage : innerCurrent.value
)
const effectiveSize = computed(() =>
  isSizePassed ? props.pageSize : innerSize.value
)

/** 按计划顺序发射/回写事件（受控才外发 update:*，非受控改内部态） */
function applyPlan(plan) {
  for (const e of plan) {
    if (e.type === 'update:current-page') {
      if (isCurrentPassed && hasCurrentUpdater) emit('update:current-page', e.value)
      if (!isCurrentPassed) innerCurrent.value = e.value
    } else if (e.type === 'update:page-size') {
      if (isSizePassed && hasSizeUpdater) emit('update:page-size', e.value)
      if (!isSizePassed) innerSize.value = e.value
    } else {
      emit(e.type, e.value)
    }
  }
}

function onSizeChange(v) {
  if (v === effectiveSize.value) return
  applyPlan(sizeChangeEventPlan(v))
}

function onPagerChange(v) {
  applyPlan(jumpEventPlan(v))
}

function goFirst() {
  if (isFirstPage(effectiveCurrent.value)) return
  applyPlan(jumpEventPlan(1))
}

function goLast() {
  const target = lastPageOf(props.total, effectiveSize.value)
  if (isLastPage(effectiveCurrent.value, props.total, effectiveSize.value)) return
  applyPlan(jumpEventPlan(target))
}
</script>

<style scoped>
/* 右对齐（spec「分页控件右对齐」）——布局在封装根上一处解决，不改全局 EP */
.ap-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  white-space: nowrap;
}
.ap-total {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.ap-sizes {
  width: 110px;
}
/* «» 与 EP 分页按钮同风格（32×32、EP 变量配色） */
.ap-btn {
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: 2px;
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 32px;
  cursor: pointer;
}
.ap-btn:hover:not(:disabled) {
  color: var(--el-color-primary);
}
.ap-btn:disabled {
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
}
</style>
