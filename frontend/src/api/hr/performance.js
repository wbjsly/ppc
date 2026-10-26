import request from '@/utils/request'

// ---------- 绩效方案（10.10.1） ----------
export function planList(params) {
  return request({ url: '/api/hr/performance/plans', method: 'get', params })
}

export function createPlan(data) {
  return request({ url: '/api/hr/performance/plans', method: 'post', data })
}

export function voidPlan(id) {
  return request({ url: `/api/hr/performance/plans/${id}/void`, method: 'post' })
}

// ---------- 评分录入（10.10.2） ----------
export function scoreList(params) {
  return request({ url: '/api/hr/performance/scores', method: 'get', params })
}

export function enterScore(data) {
  return request({ url: '/api/hr/performance/scores', method: 'post', data })
}

export function submitScore(id) {
  return request({ url: `/api/hr/performance/scores/${id}/submit`, method: 'post' })
}

export function batchSubmit(scoreIds) {
  return request({ url: '/api/hr/performance/scores/batch-submit', method: 'post', data: { scoreIds } })
}

// ---------- 评分校准（10.10.3） ----------
export function calibrate(data) {
  return request({ url: '/api/hr/performance/scores/calibrate', method: 'post', data })
}

export function confirmScores(scoreIds) {
  return request({ url: '/api/hr/performance/scores/confirm', method: 'post', data: { scoreIds } })
}

export function scoreLogs(id) {
  return request({ url: `/api/hr/performance/scores/${id}/logs`, method: 'get' })
}

// ---------- 绩效结果（10.10.4） ----------
export function resultList(params) {
  return request({ url: '/api/hr/performance/results', method: 'get', params })
}
