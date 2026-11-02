/** 1.7 版本中心历史回溯与版本回滚两页共用的版本操作展示辅助。 */
export const opTypeLabel = t => ({ CREATE: '创建', UPDATE: '变更', DISABLE: '停用', ROLLBACK: '回滚' }[t] || t)
export const opTypeTag = t => ({ CREATE: 'success', UPDATE: 'primary', DISABLE: 'info', ROLLBACK: 'warning' }[t] || 'info')
