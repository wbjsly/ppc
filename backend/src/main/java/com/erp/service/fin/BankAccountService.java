package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/** 银行账户（2.7.3，spec bank-account）：查询与付款执行的余额校验来源。 */
public interface BankAccountService {

    /** 账户列表（含余额，付款下拉与 2.7.3 展示用） */
    List<Map<String, Object>> list();

    /** 账户详情 */
    Map<String, Object> detail(String id);
}
