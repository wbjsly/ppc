import request from '@/utils/request'

/** 业务参数只读列表（erp_sys_param，D13 通用参数表） */
export function getParamsApi(params) {
  return request({ url: '/system/params', method: 'get', params })
}
