package com.erp.service.fin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 记账汇率取数（add-fx-revaluation，design D1）：按（基础币/报价币/日期）严格取中间价，
 * 缺失抛 L1（C-4.6-03 口径）。AP/AR 发票登记与外币账户开户三处共用单点取数。
 * 不走 trial 的 MIDDLE→BUY→SELL 回退——记账汇率必须是中间价这一客观事实。
 */
public interface FxRateService {

    /**
     * 取某日生效的中间价（闭区间命中唯一，区间模型不重叠保证确定性）。
     *
     * @param baseCcy 基础币（如 USD）
     * @param quoteCcy 报价币（本位币 CNY）
     * @param date 取数日期（发票日/开户日/期末日）
     * @return 中间价 6 位精度
     * @throws com.erp.common.ServiceException 422 该日无生效中间价
     */
    BigDecimal requireMiddleRate(String baseCcy, String quoteCcy, LocalDate date);
}
