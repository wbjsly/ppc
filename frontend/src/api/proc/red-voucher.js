import request from '@/utils/request'

/** 红字凭证台账（2.6.3，spec red-receipt-voucher）——只读 */

export function getRedVoucherPageApi(params) {
  return request.get('/proc/returns/red-vouchers', { params })
}
