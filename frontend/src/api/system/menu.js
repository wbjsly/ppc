import request from '@/utils/request'

export function getUserMenusApi() {
  return request.get('/system/menus/user')
}

export function getMenuListApi() {
  return request.get('/system/menus')
}

export function getMenuApi(id) {
  return request.get(`/system/menus/${id}`)
}

export function createMenuApi(data) {
  return request.post('/system/menus', data)
}

export function updateMenuApi(data) {
  return request.put('/system/menus', data)
}

export function deleteMenuApi(id) {
  return request.delete(`/system/menus/${id}`)
}
