package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 汇率主数据（汇率管理 1.5.1，表 erp_mdm_exchange_rate，FR-4.1-3-1）。
 * 序列键 = BASE_CCY + QUOTE_CCY + RATE_TYPE（三类独立区间链，design D2）；
 * 闭区间 [EFFECTIVE_DATE, EXPIRE_DATE] 均必填；无状态列（计算态按日期推导）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_exchange_rate")
public class MdmExchangeRate extends BaseEntity {

    /** ISO 4217 3 位大写（如 CNY） */
    private String baseCcy;

    /** ISO 4217 3 位大写（如 USD），1 BASE = RATE QUOTE */
    private String quoteCcy;

    /** MIDDLE 中间价 / BUY 买入价 / SELL 卖出价 */
    private String rateType;

    private LocalDate effectiveDate;

    private LocalDate expireDate;

    /** 6 位小数精度（FR-4.6-4-3），> 0 */
    private BigDecimal rate;

    /** 来源文件编号（央行公告编号，C-4.1-04 必附） */
    private String sourceFileNo;

    /** 变更原因（编辑时必填），非持久化，写入版本记录 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient String changeReason;
}
