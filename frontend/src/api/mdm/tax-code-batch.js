import request from '@/utils/request'

/** 行级预检（dry-run 不落库） */
export function previewTaxBatch(rows, defaultPolicyNo) {
  return request.post('/mdm/tax-code-batch/preview', { rows, defaultPolicyNo })
}

/** 按行独立提交（部分失败不回滚） */
export function submitTaxBatch(rows, defaultPolicyNo) {
  return request.post('/mdm/tax-code-batch/batch', { rows, defaultPolicyNo })
}
