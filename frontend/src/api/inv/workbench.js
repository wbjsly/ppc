import request from '@/utils/request'

/**
 * 入库作业台任务（spec inbound-workbench）：按类型聚合来源单据头。
 * type：PURCHASE_IN / SALES_RETURN_IN / 未建域类型（空态骨架）
 */
export function getWorkbenchTasksApi(params) {
  return request.get('/inv/workbench/tasks', { params })
}
