import test from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_PAGE_SIZES,
  DEFAULT_PAGE_SIZE,
  lastPageOf,
  isFirstPage,
  isLastPage,
  sizeChangeEventPlan,
  jumpEventPlan,
} from '../src/components/pagination-logic.js'

test('每页条数枚举与默认值符合 spec', () => {
  assert.deepEqual(DEFAULT_PAGE_SIZES, [10, 20, 50, 100, 200])
  assert.equal(DEFAULT_PAGE_SIZE, 20)
})

test('末页计算：ceil(total/size) 且至少 1', () => {
  assert.equal(lastPageOf(130, 20), 7)
  assert.equal(lastPageOf(40, 20), 2)
  assert.equal(lastPageOf(41, 20), 3)
  assert.equal(lastPageOf(0, 20), 1)
  assert.equal(lastPageOf(null, 20), 1)
  // pageSize 缺省/非法 → 按 20
  assert.equal(lastPageOf(45, null), 3)
  assert.equal(lastPageOf(45, 0), 3)
  // 换尺寸后页数按新条数重算（spec「切换条数后请求携带新值」）
  assert.equal(lastPageOf(130, 50), 3)
  assert.equal(lastPageOf(130, 100), 2)
})

test('首末页端点判定（«» 禁用态）', () => {
  assert.equal(isFirstPage(1), true)
  assert.equal(isFirstPage(0), true)
  assert.equal(isFirstPage(7), false)
  assert.equal(isLastPage(7, 130, 20), true)
  assert.equal(isLastPage(6, 130, 20), false)
  // 总数为 0：唯一第 1 页同时是首也是末 → 两个按钮都禁用
  assert.equal(isLastPage(1, 0, 20), true)
})

test('尺寸切换事件序列（design D3：第1页与第N页统一回第1页并补发加载）', () => {
  const plan = sizeChangeEventPlan(100)
  assert.deepEqual(plan, [
    { type: 'update:page-size', value: 100 },
    { type: 'size-change', value: 100 },
    { type: 'update:current-page', value: 1 },
    { type: 'current-change', value: 1 },
  ])
  // 无论此前页码，序列恒为回第1页 + 补发（第1页切尺寸也必触发 current-change）
  assert.equal(plan.at(-1).type, 'current-change')
  assert.equal(plan.at(-1).value, 1)
})

test('首/末页跳转事件序列', () => {
  assert.deepEqual(jumpEventPlan(1), [
    { type: 'update:current-page', value: 1 },
    { type: 'current-change', value: 1 },
  ])
  assert.deepEqual(jumpEventPlan(7), [
    { type: 'update:current-page', value: 7 },
    { type: 'current-change', value: 7 },
  ])
})
