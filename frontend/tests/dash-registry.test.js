/**
 * 工作台 widget 目录单测（add-configurable-workbench 任务 2.2，
 * spec personal-workbench「组件库注册与角色可见性」）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  DASH_WIDGETS, visibleWidgets, addableWidgets, widgetById,
} from '../src/views/dashboard/dash-registry.js'

const ADMIN = ['ROLE_ADMIN', 'ROLE_PMO']
const NORMAL = ['ROLE_USER']
const NO_ROLE = []

test('目录规模：10 个 ready + 至少 2 个 planned 占位', () => {
  const ready = DASH_WIDGETS.filter(w => w.status === 'ready')
  const planned = DASH_WIDGETS.filter(w => w.status === 'planned')
  assert.equal(ready.length, 10, '首批 10 个 widget')
  assert.ok(planned.length >= 2, '占位至少 2 个')
})

test('ADMIN 全量可见（含越权告警与占位）', () => {
  assert.equal(visibleWidgets(ADMIN).length, DASH_WIDGETS.length)
})

test('普通角色不可见越权安全告警（perm 预过滤，不发 403 请求）', () => {
  const ids = visibleWidgets(NORMAL).map(w => w.id)
  assert.ok(!ids.includes('authz-alert'), '无 ROLE_ADMIN 不出现越权告警')
  assert.ok(ids.includes('todo'), '无标签组件照常可见')
})

test('零角色账号：仅无标签组件可见', () => {
  const ids = visibleWidgets(NO_ROLE).map(w => w.id)
  assert.ok(ids.length > 0, '无标签组件对任何登录用户可见')
  assert.ok(!ids.includes('authz-alert'))
})

test('planned 占位可见但不可加入画布（灰显不可拖入，spec 场景）', () => {
  const visible = visibleWidgets(ADMIN).map(w => w.id)
  assert.ok(visible.includes('sales-funnel'), '占位在选择列表灰显可见')
  const addable = addableWidgets(ADMIN).map(w => w.id)
  assert.ok(!addable.includes('sales-funnel'), '占位不可加入')
  assert.ok(!addable.includes('stock-alert'), '占位不可加入')
  assert.equal(addable.length, 10, '可加入 = 10 个 ready')
})

test('perm 标签与后端 URL 规则一致（任务 3.4 核对）', () => {
  assert.deepEqual(widgetById('authz-alert').perm, ['ROLE_ADMIN'], 'authz-incidents hasRole(ADMIN)')
  assert.deepEqual(widgetById('todo').perm, [], '/api/iot/** authenticated')
  assert.deepEqual(widgetById('period').perm, ['ROLE_ADMIN', 'ROLE_PM'],
    '/api/fin/** GET hasAnyRole(ADMIN,PM)——漏标会让无角色用户 403')
  // 零角色画布：period/authz 均不可见（本地预过滤，不发 403 请求）
  const ids = visibleWidgets([]).map(w => w.id)
  assert.ok(!ids.includes('period'))
  assert.ok(!ids.includes('authz-alert'))
})

test('未知 id 返回 null', () => {
  assert.equal(widgetById('not-exist'), null)
})
