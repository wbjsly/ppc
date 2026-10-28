import request from '@/utils/request'

// ============================================================
// M12.4 报文处理操作台（add-edi-message-console）
// 复用 interface-integration 既有端点封装（收口仅动页面，后端零改动）；
// detail 返回体已扩展 retryTrace / receipts（design D2）。
// ============================================================

export {
  getMessagesApi,
  getMessageDetailApi,
  uploadMessageApi,
  simulatePushApi,
  replayMessageApi,
  getMapRulesApi,
  saveMapRuleApi
} from './interface-integration'
