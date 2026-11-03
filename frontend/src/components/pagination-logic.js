/**
 * pagination-logic.js — AppPagination 纯逻辑（add-unified-pagination，design D3）
 * 与组件解耦供 node:test 直击：末页计算、端点判定、尺寸切换与首末页跳转的事件序列。
 */

export const DEFAULT_PAGE_SIZES = [10, 20, 50, 100, 200]
export const DEFAULT_PAGE_SIZE = 20

/** 末页数：ceil(total / pageSize)，至少 1；pageSize 缺省按 20 */
export function lastPageOf(total, pageSize) {
  const t = Number(total) || 0
  const s = Number(pageSize) || DEFAULT_PAGE_SIZE
  return Math.max(1, Math.ceil(t / s))
}

export function isFirstPage(current) {
  return (Number(current) || 1) <= 1
}

export function isLastPage(current, total, pageSize) {
  return (Number(current) || 1) >= lastPageOf(total, pageSize)
}

/**
 * 尺寸切换事件序列（顺序即组件发射顺序；design D3 关键）：
 * 先回写 size，再强制回第 1 页，最后补发 current-change——
 * 无论此前在第几页都触发页面既有加载器（第 1 页切尺寸也重载的场景由此闭环）。
 */
export function sizeChangeEventPlan(newSize) {
  return [
    { type: 'update:page-size', value: newSize },
    { type: 'size-change', value: newSize },
    { type: 'update:current-page', value: 1 },
    { type: 'current-change', value: 1 },
  ]
}

/** 首/末页跳转事件序列：回写页码 + 触发加载 */
export function jumpEventPlan(targetPage) {
  return [
    { type: 'update:current-page', value: targetPage },
    { type: 'current-change', value: targetPage },
  ]
}
