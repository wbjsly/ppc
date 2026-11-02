import { ref } from 'vue'

/**
 * 13.8 预测维护两页共享状态（拆分自原预测维护操作台，design D2 composable 规则）。
 * 模块级单例：结果条（告警联动建单、维修工单闭环写入、两页底部展示）共享，
 * 等价原「同一组件被两条路由复用 + keep-alive」的行为。
 */
const result = ref(null)

export function usePredictive() {
  return { result }
}
