/**
 * 离线同步队列（add-iot-mobile 5.4，IndexedDB + AES-GCM 字段加密）。
 * 纯函数（chunkRecords/remainHours/earliestPending）可独立单测；
 * IDB 部分在浏览器环境执行，node 下惰性降级（无 IndexedDB 时队列空）。
 */
import { deriveKey, encryptField, decryptField } from './crypto.js'

const DB_NAME = 'iot-offline'
const STORE = 'queue'
const PENDING = 'PENDING'
const DEFAULT_MAX_HOURS = 72

// ---------- 纯函数（单测覆盖） ----------

/** 100 条/批分批（FR-4.11-4-2 要点①：每批次最多 100 条） */
export function chunkRecords(records, size = 100) {
  const out = []
  for (let i = 0; i < records.length; i += size) {
    out.push(records.slice(i, i + size))
  }
  return out
}

/** 最早待同步记录（返回 null 表示无待同步） */
export function earliestPending(records) {
  let min = null
  for (const r of records) {
    if (r.status !== PENDING) {
      continue
    }
    const ts = r.clientTs instanceof Date ? r.clientTs.getTime() : new Date(r.clientTs).getTime()
    if (Number.isNaN(ts)) {
      continue
    }
    if (min === null || ts < min) {
      min = ts
    }
  }
  return min
}

/**
 * 锁定倒计时（C-4.11-04）：
 * 返回剩余小时数（浮点）；无待同步 → null；
 * 已超期 → 负数（调用方据此锁定）。
 */
export function remainHours(records, maxHours = DEFAULT_MAX_HOURS, now = Date.now()) {
  const earliest = earliestPending(records)
  if (earliest === null) {
    return null
  }
  const elapsed = (now - earliest) / 3600000
  return Number((maxHours - elapsed).toFixed(2))
}

/** 预警判定（OFFLINE_WARN_RATIO，剩余 ≤ (1-ratio)×maxHours 黄闪） */
export function shouldWarn(records, maxHours = DEFAULT_MAX_HOURS, ratio = 0.75, now = Date.now()) {
  const remain = remainHours(records, maxHours, now)
  if (remain === null) {
    return false
  }
  return remain <= maxHours * (1 - ratio)
}

// ---------- IDB 队列（浏览器） ----------

let sessionKey = null

function hasIDB() {
  return typeof indexedDB !== 'undefined'
}

function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: 'idempotentKey' })
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function tx(mode, fn) {
  const db = await openDB()
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode)
    const store = t.objectStore(STORE)
    const out = fn(store)
    t.oncomplete = () => resolve(out && out.result !== undefined ? out.result : out)
    t.onerror = () => reject(t.error)
  })
}

/** 入队（payload 字段 AES-GCM 加密；幂等键相同直接忽略——抗重复入队） */
export async function enqueue(record) {
  if (!hasIDB()) {
    return false
  }
  sessionKey = sessionKey || await deriveKey(record.sessionSeed || 'iot-session')
  const payload = await encryptField(sessionKey, JSON.stringify(record.payload || {}))
  const row = {
    idempotentKey: record.idempotentKey,
    deviceCode: record.deviceCode || 'PDA-WEB',
    bizType: record.bizType || 'SCAN',
    bizKey: record.bizKey || null,
    payloadEnc: payload,
    clientVerNo: record.clientVerNo || 0,
    clientTs: record.clientTs || new Date().toISOString(),
    status: PENDING,
    retryCnt: 0,
  }
  try {
    await tx('readwrite', store => store.add(row))
    return true
  } catch (e) {
    // ConstraintError = 幂等键已存在 → 抗重复入队
    return false
  }
}

/** 待同步条数（PdaShell 同步栏消费） */
export async function pendingCount() {
  if (!hasIDB()) {
    return 0
  }
  const rows = await getAll()
  return rows.filter(r => r.status === PENDING).length
}

/** 72h 锁定剩余小时（无待同步 → null） */
export async function lockRemainHours(maxHours = DEFAULT_MAX_HOURS) {
  if (!hasIDB()) {
    return null
  }
  const rows = await getAll()
  return remainHours(rows.map(r => ({ status: r.status, clientTs: r.clientTs })), maxHours)
}

async function getAll() {
  try {
    return await tx('readonly', store => store.getAll())
  } catch (e) {
    return []
  }
}

/**
 * 分批上传（调用方传 api(batch)）：PENDING 记录解密回 payload → 100 条/批 →
 * 按响应标记 SYNCED/FAILED/CONFLICT。
 */
export async function uploadQueue(api) {
  if (!hasIDB()) {
    return { uploaded: 0 }
  }
  sessionKey = sessionKey || await deriveKey('iot-session')
  const rows = (await getAll()).filter(r => r.status === PENDING)
  const batches = chunkRecords(rows, 100)
  let uploaded = 0
  for (const batch of batches) {
    const payload = []
    for (const r of batch) {
      const plain = await decryptField(sessionKey, r.payloadEnc)
      payload.push({
        idempotentKey: r.idempotentKey,
        bizType: r.bizType,
        bizKey: r.bizKey,
        deviceCode: r.deviceCode,
        clientTs: r.clientTs,
        clientVerNo: r.clientVerNo,
        payload: plain,
      })
    }
    const res = await api(payload)
    const results = (res && res.results) || []
    for (const one of results) {
      const row = rows.find(x => x.idempotentKey === one.idempotentKey)
      if (!row) {
        continue
      }
      if (one.result === 'SYNCED' || one.result === 'DUPLICATE') {
        row.status = 'SYNCED'
        uploaded++
      } else if (one.result === 'CONFLICT') {
        row.status = 'CONFLICT'
      } else {
        row.status = 'FAILED'
        row.errMsg = one.detail
        row.retryCnt = (row.retryCnt || 0) + 1
      }
      await tx('readwrite', store => store.put(row))
    }
  }
  return { uploaded, batches: batches.length }
}

/** 清空本地缓存（服务端 cleanup 确认后调用） */
export async function clearSynced() {
  if (!hasIDB()) {
    return 0
  }
  const rows = (await getAll()).filter(r => r.status === 'SYNCED' || r.status === 'FAILED')
  for (const r of rows) {
    await tx('readwrite', store => store.delete(r.idempotentKey))
  }
  return rows.length
}
