import request from '@/utils/request'

// ================= 试产申请单（trial-order FR-4.5-8-1/2） =================

/** 试产申请建档（阶段/数量/验证目标校验；阶段闸口 DVT←EVT、PVT←DVT） */
export function createApplyApi(head) {
  return request.post('/mrp/trial/apply/create', head)
}

/** 提交申请审批（工艺主管单节点；驳回回草稿可重提） */
export function submitApplyApi(trialNo) {
  return request.post('/mrp/trial/apply/submit', { trialNo })
}

/** 申请单列表（阶段/状态/产品过滤） */
export function getApplyListApi(params) {
  return request.get('/mrp/trial/apply/list', { params })
}

// ================= 转开与工单（trial-order FR-4.5-8-3 / C-4.5-14） =================

/** 1:1 转开 TP- 工单（C-4.5-14 卡控链 + PVT diff 审批） */
export function convertApi(trialNo, bomId, routingId, ackFormalBom) {
  return request.post('/mrp/trial/convert', {
    trialNo, bomId: bomId || null, routingId: routingId || null, ackFormalBom: !!ackFormalBom
  })
}

/** 试产工单列表（TRIAL 分池；阶段/状态过滤） */
export function getTrialMoListApi(params) {
  return request.get('/mrp/trial/list', { params })
}

/** 生命周期阶段推导（执行中/待检验/待入库/待评审/待关闭/终态） */
export function getTrialPhaseApi(moId) {
  return request.get(`/mrp/trial/phase/${moId}`)
}

// ================= 数据采集（trial-execution FR-4.5-8-5） =================

/** 采集表单（带出 5.7 CONFIRMED 报工工时；工序校验） */
export function getDataFormApi(moId, opSeq) {
  return request.get('/mrp/trial/data/form', { params: { moId, opSeq } })
}

/** 采集 upsert（按工单+工序） */
export function saveDataApi(row) {
  return request.post('/mrp/trial/data/save', row)
}

/** 工单采集行列表 */
export function getDataListApi(moId) {
  return request.get(`/mrp/trial/data/${moId}`)
}

// ================= 总结与评审（trial-execution FR-4.5-8-6） =================

/** 总结建档/刷新（三指标服务端计算，客户端指标字段忽略） */
export function saveSummaryApi(row) {
  return request.post('/mrp/trial/summary/save', row)
}

/** 提交跨部门评审（工艺/质量/计划三并行会签；PVT 良率闸口前置） */
export function submitSummaryApi(summaryNo) {
  return request.post('/mrp/trial/summary/submit', { summaryNo })
}

/** 总结列表（阶段/状态过滤） */
export function getSummaryListApi(params) {
  return request.get('/mrp/trial/summary/list', { params })
}

// ================= 转量产与成本关单（trial-execution FR-4.5-8-7/8） =================

/** 转量产复制（评审 APPROVED 才可转；克隆量产草稿记 SRC_TRIAL_NO） */
export function promoteApi(moId) {
  return request.post('/mrp/trial/promote', { moId })
}

/** 试产成本明细与汇总（net = 归集 − 已确认冲回；PENDING 剔除） */
export function getTrialCostApi(moId) {
  return request.get(`/mrp/trial/cost/${moId}`)
}

/** 剩余物料明细（POSTED 领料 × TRIAL_FLAG=1 批次 × 可用余量） */
export function getRemainderApi(moId) {
  return request.get(`/mrp/trial/remainder/${moId}`)
}

/** 剩余物料确认留痕（关单 L2 确认闸） */
export function ackRemainderApi(moId, note) {
  return request.post('/mrp/trial/remainder/ack', { moId, note })
}

// ================= 试产版 BOM/路线（BomController / RoutingController） =================

/** 提交试产特别审批（TrialBom 节点 PROCESS_MGR 签，通过入 TRIAL 态） */
export function submitTrialBomApi(bomId) {
  return request.post(`/mrp/boms/${bomId}/submit-trial`)
}

/** 提交试产路线特别审批（TrialRoute 节点 PROCESS_MGR 签） */
export function submitTrialRoutingApi(routingId) {
  return request.post(`/mrp/routings/${routingId}/submit-trial`)
}
