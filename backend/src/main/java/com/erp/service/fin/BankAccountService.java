package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/** 银行账户（2.7.3，spec bank-account）：查询与付款执行的余额校验来源。 */
public interface BankAccountService {

    /** 账户列表（含余额，付款下拉与 2.7.3 展示用） */
    List<Map<String, Object>> list();

    /** 账户详情 */
    Map<String, Object> detail(String id);

    /**
     * 开户（add-fx-revaluation task 2.4，spec bank-account MODIFIED）：
     * 校验账号唯一/币种 ISO 码 → 外币按开户日中间价固化 BOOKING_RATE + 写 FC_BALANCE（缺汇率 L1 阻断）；
     * CNY 账户 FC_BALANCE≡BALANCE、BOOKING_RATE≡1。
     * payload：accountName/accountNo 必填，currency 默认 CNY，balance 期初余额（外币账户为外币额），openDate 开户日默认当日。
     */
    Map<String, Object> create(Map<String, Object> payload);
}
