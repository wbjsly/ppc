package com.erp.service.fin;

import java.time.LocalDate;
import java.util.Map;

/** 资金预测（8.3.3，spec ap-management：滚动 7/30/90 天未清应付+已排期+预付占用−银行余额→缺口） */
public interface ApForecastService {

    /**
     * 资金需求预测（baseDate 默认今天）。
     * 未来 N 天窗口：按 DUE_DATE 汇总未清应付（POSTED 且未清>0）
     *   + 已排期待付（payment_request SCHEDULED 且 PLAN_DATE 落窗内）
     *   + 预付款占用（prepayment 已执行未冲抵余额）
     *   − 银行账户可用余额（ACTIVE 汇总）
     * 返回 {baseDate, windows:[{days,apDue,scheduled,prepay,need,bank,gap}...], bankTotal}
     */
    Map<String, Object> forecast(LocalDate baseDate);
}
