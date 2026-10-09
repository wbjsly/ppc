import request from '@/utils/request'

/** 仓库属性字典（4.1.3，spec warehouse-attribute-config）五类白名单 */

/** 五类字典类型（与后端 InvDictServiceImpl.ALLOWED_TYPES 同口径） */
export const INV_DICT_TYPES = [
  { type: 'WAREHOUSE_TYPE', label: '仓库类型' },
  { type: 'BIN_TYPE', label: '仓位类型' },
  { type: 'TEMP_LEVEL', label: '温湿度等级' },
  { type: 'HAZARD_LEVEL', label: '危化品等级' },
  { type: 'CLEAN_LEVEL', label: '洁净等级' }
]

export function getDictItemsApi(params) {
  return request.get('/inv/dicts', { params })
}

/** 启用条目（区域/仓位/仓库表单下拉消费；停用条目不返回） */
export function getDictActiveApi(dictType) {
  return request.get('/inv/dicts/active', { params: { dictType } })
}

export function createDictItemApi(data) {
  return request.post('/inv/dicts', data)
}

export function updateDictItemApi(data) {
  return request.put('/inv/dicts', data)
}

export function enableDictItemApi(id) {
  return request.put(`/inv/dicts/${id}/enable`)
}

export function disableDictItemApi(id) {
  return request.put(`/inv/dicts/${id}/disable`)
}
