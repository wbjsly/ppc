/**
 * 翻译缺口批量异步回传器（BR-5.7-05 / C-5.7-07）。
 * 内存 Map{key → {firstHitAt, count}} 聚合 → 30s 定时或满 50 条批量 POST /api/i18n/gaps/batch。
 * 后端 UPSERT 累加（FIRST_HIT_AT 不变）。高频键（周>500）由后端自动升 P1。
 */
import request from '@/utils/request'

const AGG = new Map() // key -> { keyName, moduleName, priority, firstHitAt, hitCount }
const FLUSH_MS = 30000
const FLUSH_MAX = 50
let timer = null

function ensure(key, priority) {
  let e = AGG.get(key)
  if (!e) {
    e = {
      keyName: key,
      moduleName: (key || '').split('.')[0] || 'unknown',
      priority: priority || 'P2',
      firstHitAt: new Date().toISOString().slice(0, 19).replace('T', ' '),
      hitCount: 0
    }
    AGG.set(key, e)
  }
  return e
}

/** 记录一次缺失命中（渲染层调用） */
export function recordMissing(key, priority) {
  const e = ensure(key, priority)
  e.hitCount += 1
  if (AGG.size >= FLUSH_MAX) {
    flush()
  }
}

/** 批量回传（失败静默重试下一轮，不阻塞渲染） */
export async function flush() {
  if (AGG.size === 0) return 0
  const items = Array.from(AGG.values()).map(e => ({ ...e }))
  AGG.clear()
  try {
    await request.post('/i18n/gaps/batch', items)
    return items.length
  } catch (e) {
    // 失败回填继续攒（避免丢计数）
    items.forEach(it => {
      const cur = ensure(it.keyName, it.priority)
      cur.hitCount += it.hitCount
    })
    return 0
  }
}

/** 启动定时批量回传（应用初始化时调用一次） */
export function startCollector() {
  if (timer) return
  timer = setInterval(() => { flush() }, FLUSH_MS)
}

export function stopCollector() {
  if (timer) { clearInterval(timer); timer = null }
}
