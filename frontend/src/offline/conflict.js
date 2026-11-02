/**
 * 冲突裁决展示与交互（add-iot-mobile 5.4）：
 * 纯函数 dictSeqKey/dictCompare 实现 BL-4.11-01 字典序
 * （VER_NO > CLIENT_TS > IDEMPOTENT_KEY），与后端裁决同序。
 */

/** 字典序裁决键（与后端 IotOfflineSyncServiceImpl.dictKey 同构） */
export function dictSeqKey(record) {
  const v = String(record.clientVerNo || 0).padStart(3, '0')
  const ts = record.clientTs ? String(record.clientTs) : ''
  return `V${v}|${ts}|${record.idempotentKey || ''}`
}

/**
 * 两条记录比较：负= a 败 /正= a 胜 / 0= 相等。
 * 字典序：VER_NO → CLIENT_TS → IDEMPOTENT_KEY。
 */
export function dictCompare(a, b) {
  const va = a.clientVerNo || 0
  const vb = b.clientVerNo || 0
  if (va !== vb) {
    return va - vb
  }
  const ta = a.clientTs ? new Date(a.clientTs).getTime() : 0
  const tb = b.clientTs ? new Date(b.clientTs).getTime() : 0
  if (ta !== tb) {
    return ta - tb
  }
  const ka = a.idempotentKey || ''
  const kb = b.idempotentKey || ''
  return ka < kb ? -1 : ka > kb ? 1 : 0
}

/**
 * 冲突类型判定（与后端 detectConflict 同规则）：
 * 数量类无超容差标记 → MERGE；overTolerance → DIFF；其余 LATEST；
 * 载荷含 amount → 财务 NEED_FIN_APPROVAL。
 */
export function classifyConflict(payload) {
  const text = typeof payload === 'string' ? payload : JSON.stringify(payload || {})
  const financial = text.includes('"amount"')
  const overTolerance = text.includes('"overTolerance":true')
  const numeric = text.includes('"qty"')
  let type = 'LATEST'
  if (numeric && !overTolerance) {
    type = 'MERGE'
  } else if (overTolerance) {
    type = 'DIFF'
  }
  return { type, needFinApproval: financial ? 1 : 0 }
}

/** 中文提示语映射（移动端异常码展示） */
export function conflictHint(conflict) {
  const base = {
    MERGE: '数量类冲突：自动合并累加（快照已留痕）',
    LATEST: '状态类冲突：以最新时间戳为准',
    DIFF: '超容差差异：待人工处理（L3）',
    REVIEW: '人工复核',
  }[conflict.conflictType] || '冲突待处理'
  return conflict.needFinApproval === '1' || conflict.needFinApproval === 1
      ? base + ' ·【L2 财务审批】需财务主管批准后放行'
      : base
}
