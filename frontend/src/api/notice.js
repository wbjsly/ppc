import request from '@/utils/request'

/**
 * 站内通知中心（change add-kitting-check design D4：
 * 端点自 /api/crm/notices 正名迁出至 /api/notices，服务层 NoticeService 零改动）。
 */

/** 我的通知（按角色聚合，未读在前、时间倒序，上限 100 条） */
export function getNoticesApi() {
  return request.get('/notices')
}

/** 未读数（铃铛角标） */
export function getUnreadCountApi() {
  return request.get('/notices/unread-count')
}

/** 标记已读（仅本人可见通知；越权 403、不存在 404） */
export function markNoticeReadApi(id) {
  return request.put(`/notices/${id}/read`)
}
