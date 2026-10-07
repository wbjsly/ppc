import request from '@/utils/request'

/** 银行账户（2.7.3，spec bank-account）——只读，余额在付款执行时条件扣减 */

export function getBankAccountsApi() {
  return request.get('/fin/bank-accounts')
}

export function getBankAccountApi(id) {
  return request.get(`/fin/bank-accounts/${id}`)
}
