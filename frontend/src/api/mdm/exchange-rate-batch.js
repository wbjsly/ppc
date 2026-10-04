import request from '@/utils/request'

/** 行级预检（dry-run 不落库） */
export function previewBatchApi(rows, defaultSourceFileNo) {
  return request.post('/mdm/exchange-rate-batch/preview', { rows, defaultSourceFileNo })
}

/** 按行独立提交（部分失败不回滚成功行） */
export function submitBatchApi(rows, defaultSourceFileNo) {
  return request.post('/mdm/exchange-rate-batch/batch', { rows, defaultSourceFileNo })
}
