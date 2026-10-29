import request from '@/utils/request'

/**
 * 工作日历与班次（16.11，change add-dispatch-management，spec capacity-load）。
 * 写操作仅 ADMIN（后端 service 层校验，PLANNER 写 403）；读取登录即可。
 */

/** 日期区间日历（from/to 均含；未维护日期不在结果中，Fallback 判定在排程侧） */
export function getCalendarDatesApi(from, to) {
  return request.get('/system/calendar', { params: { from, to } })
}

/** 维护单日（upsert；isWorkday 0/1，修改留痕） */
export function upsertCalendarDateApi(date, isWorkday, remark) {
  return request.put(`/system/calendar/${date}`, { isWorkday, remark })
}

/** 班次模板全量 */
export function getShiftsApi() {
  return request.get('/system/calendar/shifts')
}

/** 新建班次（编码唯一、工时 > 0） */
export function createShiftApi(row) {
  return request.post('/system/calendar/shifts', row)
}

/** 修改班次（编码不可改） */
export function updateShiftApi(id, row) {
  return request.put(`/system/calendar/shifts/${id}`, row)
}

/** 删除班次（被工作中心引用 422） */
export function deleteShiftApi(id) {
  return request.delete(`/system/calendar/shifts/${id}`)
}

/** 工作中心班次绑定（wcCode 空 → 全量） */
export function getWcShiftsApi(wcCode) {
  return request.get('/system/calendar/wc-shifts', { params: { wcCode } })
}

/** 绑定/改绑（首期单班次替换式） */
export function bindWcShiftApi(wcCode, shiftCode) {
  return request.put('/system/calendar/wc-shifts', { wcCode, shiftCode })
}

/** 解绑 */
export function unbindWcShiftApi(id) {
  return request.delete(`/system/calendar/wc-shifts/${id}`)
}

// ---------- 法定假日同步与源头维护（close-work-calendar-gaps，spec capacity-load ADDED） ----------

/** 法定假日列表（HR 假日表，按日期升序） */
export function getHolidaysApi() {
  return request.get('/system/calendar/holidays')
}

/** 新增法定假日（TYPE 限 STATUTORY/RESTDAY/WORKDAY；日期唯一） */
export function createHolidayApi(row) {
  return request.post('/system/calendar/holidays', row)
}

/** 修改法定假日（日期不可改） */
export function updateHolidayApi(id, row) {
  return request.put(`/system/calendar/holidays/${id}`, row)
}

/** 删除法定假日 */
export function deleteHolidayApi(id) {
  return request.delete(`/system/calendar/holidays/${id}`)
}

/** 同步法定假日到工作日历（幂等；返回变更行数） */
export function syncHolidaysApi() {
  return request.post('/system/calendar/holidays/sync')
}
