/**
 * 网络状态与离线同步栏（add-iot-mobile，最小版供 PdaShell 使用；
 * 5.4 扩展：100 条/批上传器、72h 本地倒计时、队列集成）。
 *
 * online：浏览器真实网络事件；offline：online 事件 OR 模拟断网开关（PDA 壳顶手动开关，
 * 演示/自动化测试用——CI 无法物理断网）。
 */
import { reactive } from 'vue'

const state = reactive({
  online: typeof navigator !== 'undefined' ? navigator.onLine !== false : true,
  simOffline: false,
})

const listeners = new Set()

function notify() {
  listeners.forEach(fn => fn(isOnline()))
}

export function isOnline() {
  return state.online && !state.simOffline
}

/** 模拟断网开关（true=断网） */
export function setSimOffline(off) {
  state.simOffline = !!off
  notify()
}

/** 订阅网络状态变化，返回取消函数 */
export function onNetChange(fn) {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

if (typeof window !== 'undefined') {
  window.addEventListener('online', () => { state.online = true; notify() })
  window.addEventListener('offline', () => { state.online = false; notify() })
}

/** 待同步队列条数（5.4 接 IndexedDB；当前无队列实现时返回 0） */
export async function pendingQueueCount() {
  try {
    const q = await import('@/offline/queue')
    return await q.pendingCount()
  } catch (e) {
    return 0
  }
}

/** 72h 锁定剩余小时数（无最早待同步记录返回 null） */
export async function lockRemainHours() {
  try {
    const q = await import('@/offline/queue')
    return await q.lockRemainHours()
  } catch (e) {
    return null
  }
}
