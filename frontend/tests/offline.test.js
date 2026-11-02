/**
 * 离线底座单测（add-iot-mobile 任务 5.4）：
 * 入队加密（AES-GCM）/ 断网切换（netStatus 状态机）/
 * 分批上传（100 条/批）/ 锁定倒计时（72h 阈值）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { deriveKey, encryptField, decryptField } from '../src/offline/crypto.js'
import {
  chunkRecords, earliestPending, remainHours, shouldWarn,
} from '../src/offline/queue.js'
import { dictSeqKey, dictCompare, classifyConflict, conflictHint } from '../src/offline/conflict.js'
import { isOnline, setSimOffline } from '../src/offline/netStatus.js'

test('入队加密：AES-GCM 密文不可读且可逆', async () => {
  const key = await deriveKey('session-1')
  const plain = JSON.stringify({ dispatchId: 'DP-1', goodQty: 10 })
  const cipher = await encryptField(key, plain)
  assert.notEqual(cipher, plain, '密文≠明文')
  assert.ok(cipher.startsWith('v1.'), '密文带版本前缀')
  assert.ok(!cipher.includes('DP-1'), '密文不含明文片段')
  const back = await decryptField(key, cipher)
  assert.equal(back, plain, '解密还原')
  // 空值原样
  assert.equal(await encryptField(key, null), null)
  assert.equal(await decryptField(key, 'plain-text'), 'plain-text', '非密文原样返回')
})

test('断网切换：模拟开关驱动在线状态', () => {
  const before = isOnline()
  setSimOffline(true)
  assert.equal(isOnline(), false, '模拟断网 → 离线')
  setSimOffline(false)
  assert.equal(isOnline(), before, '恢复 → 回到真实网络态')
})

test('分批上传：100 条/批切分与边界', () => {
  const mk = n => Array.from({ length: n }, (_, i) => ({ id: i }))
  assert.deepEqual(chunkRecords(mk(250)).map(b => b.length), [100, 100, 50])
  assert.deepEqual(chunkRecords(mk(100)).map(b => b.length), [100], '整百不产生空批')
  assert.deepEqual(chunkRecords(mk(0)), [], '空列表无批')
  assert.deepEqual(chunkRecords(mk(101), 100).map(b => b.length), [100, 1], '101 越界进第二批')
})

test('锁定倒计时：72h 阈值预警与超期', () => {
  const now = Date.now()
  const h = x => new Date(now - x * 3600000).toISOString()
  // 无待同步 → null
  assert.equal(remainHours([], 72, now), null)
  // 10 小时前入队 → 剩 62h，不预警
  const recs10 = [{ status: 'PENDING', clientTs: h(10) }]
  assert.equal(remainHours(recs10, 72, now), 62)
  assert.equal(shouldWarn(recs10, 72, 0.75, now), false)
  // 60 小时前 → 剩 12h ≤ 18h（25%）→ 预警
  const recs60 = [{ status: 'PENDING', clientTs: h(60) }]
  assert.equal(shouldWarn(recs60, 72, 0.75, now), true, '剩余 ≤25% 黄闪预警')
  // 73 小时前 → 剩 -1h → 负数触发锁定
  const recs73 = [{ status: 'PENDING', clientTs: h(73) }]
  assert.ok(remainHours(recs73, 72, now) < 0, '超期倒计时为负')
  // SYNCED 不参与倒计时
  assert.equal(remainHours([{ status: 'SYNCED', clientTs: h(73) }], 72, now), null)
  // earliestPending 取最早
  assert.equal(earliestPending([{ status: 'PENDING', clientTs: h(5) },
    { status: 'PENDING', clientTs: h(50) }]), new Date(h(50)).getTime())
})

test('冲突裁决：BL-4.11-01 字典序与类型分类', () => {
  const a = { clientVerNo: 2, clientTs: '2026-11-01T10:00:00', idempotentKey: 'K-A' }
  const b = { clientVerNo: 2, clientTs: '2026-11-01T09:00:00', idempotentKey: 'K-B' }
  assert.ok(dictCompare(a, b) > 0, '同版本时间新者胜')
  assert.ok(dictCompare({ ...b, clientVerNo: 3 }, a) > 0, '版本高者胜（VER_NO 优先）')
  assert.equal(dictCompare(a, { ...a }), 0)
  assert.ok(dictSeqKey(a).startsWith('V002|'), '裁决键含补零版本')
  // 类型分类
  assert.equal(classifyConflict('{"qty":10}').type, 'MERGE')
  assert.equal(classifyConflict('{"qty":10,"overTolerance":true,"amount":5}').needFinApproval, 1)
  assert.equal(classifyConflict('{"overTolerance":true}').type, 'DIFF')
  assert.equal(classifyConflict('{"status":"CLOSED"}').type, 'LATEST')
  // 提示语
  assert.ok(conflictHint({ conflictType: 'MERGE', needFinApproval: '1' }).includes('L2'))
  assert.ok(conflictHint({ conflictType: 'DIFF', needFinApproval: '0' }).includes('人工'))
})
