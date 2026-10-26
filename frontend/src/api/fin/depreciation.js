import request from '@/utils/request'

// ================= 财务域折旧监控（M8-6-1/8.6-2，add-depreciation-monitoring） =================
/** 期间折旧状态列表（倒序；未计提期间 NOT_RUN 红字标识） */
export const periodStatuses = () => request.get('/fin/depreciation-monitor/runs')
/** 期间计提明细下钻（逐卡原值/当期折旧/累计/NBV/预警标记） */
export const periodDetails = (period) => request.get(`/fin/depreciation-monitor/runs/${period}/details`)
/** 残值预警实时扫描（C-4.6-04，当前时点不落库） */
export const residualAlerts = () => request.get('/fin/depreciation-monitor/residual-alerts')
