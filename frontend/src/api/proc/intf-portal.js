import request from '@/utils/request'

// ---- SSO（15.2.3，本系统作 IdP） ----

export function getSsoAppsApi() {
  return request({ url: '/api/intf/sso/apps', method: 'get' })
}

export function registerSsoAppApi(data) {
  return request({ url: '/api/intf/sso/apps', method: 'post', data })
}

export function changeSsoAppStatusApi(appId, status) {
  return request({ url: `/api/intf/sso/apps/${appId}/status`, method: 'put', params: { appId, status } })
}

export function issueAssertionApi(appId) {
  return request({ url: '/api/intf/sso/assertion', method: 'post', params: { appId } })
}

export function getSsoMetadataApi() {
  return request({ url: '/api/intf/sso/metadata', method: 'get' })
}

// ---- 灰度切流量（15.5.4） ----

export function getGrayReleasesApi() {
  return request({ url: '/api/intf/gray-releases', method: 'get' })
}

export function createGrayReleaseApi(data) {
  return request({ url: '/api/intf/gray-releases', method: 'post', data })
}

export function getGrayReleaseApi(id) {
  return request({ url: `/api/intf/gray-releases/${id}`, method: 'get' })
}

export function grayStepUpApi(id) {
  return request({ url: `/api/intf/gray-releases/${id}/step`, method: 'post' })
}

export function grayRollbackApi(id) {
  return request({ url: `/api/intf/gray-releases/${id}/rollback`, method: 'post' })
}
