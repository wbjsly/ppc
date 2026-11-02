import { ref } from 'vue'

/**
 * 13.6 设备监控四页共享状态（拆分自原设备监控操作台，design D2 composable 规则）。
 * 模块级单例：加载态（设备接入/阈值告警）与结果条（设备接入/数据看板写入、
 * 四页底部展示）在设备接入/数据看板/阈值告警/状态监控四页间共享，
 * 等价原「同一组件被四条路由复用 + keep-alive」的行为。
 */
const loading = ref(false)
const result = ref(null)

function ok(title, desc) {
  result.value = { type: 'success', title, desc: desc || '' }
}

export function useMonitoring() {
  return { loading, result, ok }
}
