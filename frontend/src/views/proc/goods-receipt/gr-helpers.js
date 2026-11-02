/**
 * 2.4 收货管理展示辅助（纯函数，拆分自原收货管理操作台，四页与详情抽屉共用）。
 */
export const statusName = s => ({ CREATED: '待过账', POSTED: '已过账', CANCELLED: '已作废' }[s] || s)
export const statusTag = s => ({ CREATED: 'warning', POSTED: 'success', CANCELLED: 'info' }[s])
export const tolName = t => ({ OK_OVER: '容差内', OK_SHORT: '容差内', OVER: '超交', SHORT: '短交', FREE: '免容差' }[t] || t)
export const diffName = s => ({ PENDING: '待处置', ADJUSTED: '已转调整', RETURNED: '已拒收', CLOSED: '已关闭' }[s] || s)
export const diffTag = s => ({ PENDING: 'danger', ADJUSTED: 'success', RETURNED: 'info', CLOSED: 'info' }[s])
export const adjName = s => ({ DRAFT: '草稿', PENDING_APPROVE: '待审批', APPROVED: '已批准', REJECTED: '已驳回', EXECUTED: '已执行' }[s] || s)
export const adjTag = s => ({ PENDING_APPROVE: 'warning', REJECTED: 'danger', EXECUTED: 'success' }[s])
