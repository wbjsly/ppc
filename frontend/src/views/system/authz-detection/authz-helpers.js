/** 16.8 越权检测两页共用的纯展示映射（降权状态标签） */

export const statusType = (s) => ({ ACTIVE: 'danger', RESTORED: 'success', DISABLED: 'info' }[s] || 'info')
export const statusText = (s) => ({ ACTIVE: '降权中', RESTORED: '已恢复', DISABLED: '已禁用' }[s] || s)
