import request from '@/utils/request'

// ---------- 知识产权管理（add-ip-management，M11.8） ----------

// ---- 协议（11.8.2） ----

/** 保存 IP 归属协议（合作比例合计=100% L1；后签覆盖前签） */
export function saveAgreement(data) {
  return request({ url: '/api/pm/ip/agreements', method: 'post', data })
}

/** 协议列表（按项目过滤） */
export function agreementList(params) {
  return request({ url: '/api/pm/ip/agreements', method: 'get', params })
}

/** 项目生效协议 */
export function effectiveAgreement(projectId) {
  return request({ url: '/api/pm/ip/agreements/effective', method: 'get', params: { projectId } })
}

// ---- 成果登记（11.8.1） ----

/** 保存/提交成果（WBS L1；参与人快照；判定引擎） */
export function saveAchievement(data) {
  return request({ url: '/api/pm/ip/achievements', method: 'post', data })
}

/** WBS 任务参与人提取（工时归集聚合） */
export function extractMembers(wbsId) {
  return request({ url: '/api/pm/ip/achievements/members', method: 'get', params: { wbsId } })
}

/** 成果列表（按项目/状态过滤） */
export function achievementList(params) {
  return request({ url: '/api/pm/ip/achievements', method: 'get', params })
}

/** 成果详情（含参与人清单） */
export function achievementDetail(id) {
  return request({ url: `/api/pm/ip/achievements/${id}`, method: 'get' })
}

/** 研发负责人确认（→ CONFIRMED + 自动建档） */
export function confirmAchievement(id, data) {
  return request({ url: `/api/pm/ip/achievements/${id}/confirm`, method: 'put', data })
}

/** 有异议（多方主张→自动建仲裁单；单方→法务复核） */
export function objectAchievement(id, data) {
  return request({ url: `/api/pm/ip/achievements/${id}/object`, method: 'put', data })
}

/** 法务复核结论（回待确认或改判权属） */
export function legalReview(id, data) {
  return request({ url: `/api/pm/ip/achievements/${id}/legal-review`, method: 'put', data })
}

// ---- 冲突仲裁（11.8.3） ----

/** 法务独立申请仲裁（兜底入口；在途防重 409） */
export function createArbitration(data) {
  return request({ url: '/api/pm/ip/arbitrations', method: 'post', data })
}

/** 仲裁单列表（含主张明细） */
export function arbitrationList(params) {
  return request({ url: '/api/pm/ip/arbitrations', method: 'get', params })
}

/** 提交权属主张（双方各自 record） */
export function addClaim(arbId, data) {
  return request({ url: `/api/pm/ip/arbitrations/${arbId}/claims`, method: 'post', data })
}

/** 法务出具仲裁意见（→ OPINION_ISSUED） */
export function issueOpinion(arbId, data) {
  return request({ url: `/api/pm/ip/arbitrations/${arbId}/opinion`, method: 'put', data })
}

/** 双方确认意见（拒绝 → ESCALATED_EXTERNAL） */
export function confirmOpinion(arbId, data) {
  return request({ url: `/api/pm/ip/arbitrations/${arbId}/confirm`, method: 'put', data })
}

/** 裁决回写（覆盖成果权属 + 回待确认重确认） */
export function adjudicate(arbId, data) {
  return request({ url: `/api/pm/ip/arbitrations/${arbId}/adjudicate`, method: 'put', data })
}

// ---- 资产建档（11.8.4） ----

/** 档案台账（类型/状态/到期区间筛选） */
export function assetList(params) {
  return request({ url: '/api/pm/ip/assets', method: 'get', params })
}

/** 更新申请状态（写状态历史） */
export function updateApplyStatus(assetId, data) {
  return request({ url: `/api/pm/ip/assets/${assetId}/apply-status`, method: 'put', data })
}

/** 申请状态变更历史 */
export function assetStatusLog(assetId) {
  return request({ url: `/api/pm/ip/assets/${assetId}/status-log`, method: 'get' })
}
