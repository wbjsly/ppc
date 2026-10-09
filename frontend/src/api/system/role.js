import request from '@/utils/request'

export function getRoleListApi() {
  return request.get('/system/roles')
}
