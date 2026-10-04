import request from '@/utils/request'

// ---------- 查询（后端查询前懒 sweep：截止锁价 + 公示期满生成协议） ----------

/** 招标项目分页 */
export function getTenderPageApi(params) {
  return request.get('/proc/tenders', { params })
}

/** 招标项目详情（行/投标方与资格/多轮报价/评委/评分） */
export function getTenderDetailApi(id) {
  return request.get(`/proc/tenders/${id}`)
}

/** 我的评标任务（仅当前评委本人数据） */
export function getMyTasksApi(id) {
  return request.get(`/proc/tenders/${id}/my-tasks`)
}

/** 定标审批待办（单节点，路由链 [采购总监]） */
export function getApprovalTodoApi() {
  return request.get('/proc/tenders/approvals/todo')
}

// ---------- 立项与要素 ----------

/** 立项（FR-4.2-10-1） */
export function createTenderApi(data) {
  return request.post('/proc/tenders', data)
}

/** 更新立项要素（仅 DRAFT；改招标编号会被 422 拒绝） */
export function updateTenderApi(data) {
  return request.put('/proc/tenders', data)
}

/** 开始报名 DRAFT → BIDDING */
export function startTenderApi(id) {
  return request.post(`/proc/tenders/${id}/start`)
}

/** 作废（已生成框架协议则 422） */
export function cancelTenderApi(id, reason) {
  return request.post(`/proc/tenders/${id}/cancel`, { reason })
}

// ---------- 报名与资格审查 ----------

/** 资格审查 PASS / FAIL，重算合格投标方数（BR-4.2-45） */
export function qualifyTenderApi(id, supplierId, status, note) {
  return request.post(`/proc/tenders/${id}/qualify`, { supplierId, status, note })
}

/** 追加投标方（锁价前，幂等） */
export function addTenderSuppliersApi(id, supplierIds) {
  return request.post(`/proc/tenders/${id}/suppliers`, { supplierIds })
}

/** 延长报名期（BR-4.2-45 入口一） */
export function postponeRegApi(id, newDeadline, reason) {
  return request.post(`/proc/tenders/${id}/postpone-reg`, { newDeadline, reason })
}

/** 转邀请招标（BR-4.2-45 入口二，待采购总监审批） */
export function toInviteApi(id, reason) {
  return request.post(`/proc/tenders/${id}/to-invite`, { reason })
}

/** 转邀请招标审批 */
export function inviteApprovalApi(id, approved, reason) {
  return request.post(`/proc/tenders/${id}/invite-approval`, { approved, reason })
}

// ---------- 报价与开标 ----------

/** 多轮报价（BR-4.2-06 只降不升，历史轮次只读） */
export function saveTenderQuoteApi(id, data) {
  return request.post(`/proc/tenders/${id}/quotes`, data)
}

/** 修改历史报价 —— 后端固定 422（历史只读） */
export function updateTenderQuoteApi(quoteId, data) {
  return request.put(`/proc/tenders/quotes/${quoteId}`, data)
}

/** 延长报价截止（仅锁价前） */
export function postponeTenderApi(id, newDeadline, reason) {
  return request.post(`/proc/tenders/${id}/postpone`, { newDeadline, reason })
}

/** 开标 LOCKED → EVALUATING（BR-4.2-45 不足时阻断） */
export function openTenderApi(id) {
  return request.post(`/proc/tenders/${id}/open`)
}

// ---------- 评标 ----------

/** 指定评标委员（替换式，评标开始后不可换） */
export function setJudgesApi(id, judgeUserIds) {
  return request.post(`/proc/tenders/${id}/judges`, { judgeUserIds })
}

/** 提交/保存评分（提交后锁定，BR-4.2-47） */
export function saveScoreApi(id, data) {
  return request.post(`/proc/tenders/${id}/scores`, data)
}

/** 合规审批后修改评分（留痕新旧值） */
export function amendScoreApi(id, data) {
  return request.post(`/proc/tenders/${id}/scores/amend`, data)
}

/** 评标汇总定标（最低价法 / 综合评分法算术平均） */
export function evaluateTenderApi(id) {
  return request.post(`/proc/tenders/${id}/evaluate`)
}

/** 围标/串标异常标记（人工，冻结/解除评标） */
export function setAnomalyApi(id, anomaly, note) {
  return request.post(`/proc/tenders/${id}/anomaly`, { anomaly, note })
}

// ---------- 定标审批与公示 ----------

/** 定标审批（采购总监单节点） */
export function approveAwardApi(id, approved, note) {
  return request.post(`/proc/tenders/${id}/approve-award`, { approved, note })
}

/** 登记公示异议（BR-4.2-48，valid=true 暂停协议生成） */
export function raiseObjectionApi(id, content, valid) {
  return request.post(`/proc/tenders/${id}/objections`, { content, valid })
}

/** 异议复核裁定 MAINTAIN / REBID */
export function reviewObjectionApi(id, objectionId, verdict, note) {
  return request.post(`/proc/tenders/${id}/objections/${objectionId}/review`, { verdict, note })
}
