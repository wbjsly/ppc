package com.erp.service.impl.fin;

import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmExchangeRateDao;
import com.erp.entity.mdm.MdmExchangeRate;
import com.erp.service.fin.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 记账汇率取数实现（add-fx-revaluation，design D1）。
 * 严格取 MIDDLE 中间价；无生效区间 → 422（C-4.6-03 L1，提示先维护汇率主数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxRateServiceImpl implements FxRateService {

    private final MdmExchangeRateDao rateDao;

    @Override
    public BigDecimal requireMiddleRate(String baseCcy, String quoteCcy, LocalDate date) {
        if (baseCcy == null || baseCcy.isBlank() || quoteCcy == null || quoteCcy.isBlank()
                || date == null) {
            throw new ServiceException(422, "取汇率参数不全（基础币/报价币/日期必填）");
        }
        // CNY 本位币自身不查汇率，语义上恒为 1（调用方应已短路；此处防御）
        if (baseCcy.equals(quoteCcy)) {
            return BigDecimal.ONE;
        }
        MdmExchangeRate hit = rateDao.selectHit(baseCcy, quoteCcy, "MIDDLE", date);
        if (hit == null || hit.getRate() == null) {
            throw new ServiceException(422, "汇率主数据缺失：无 " + date + " 生效的 "
                    + baseCcy + "/" + quoteCcy + " 中间价，请先维护汇率主数据（C-4.6-03）");
        }
        return hit.getRate();
    }
}
