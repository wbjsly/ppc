package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 法人主体（基础数据-组织管理 1.1.1，表 erp_mdm_legal_entity）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_legal_entity")
public class MdmLegalEntity extends BaseEntity {

    /** 主体编码，LE- + 4 位流水，创建后不可修改（C-4.1-01） */
    private String leCode;

    private String leName;

    /** 统一社会信用代码，全局唯一（C-4.1-07 查重） */
    private String uscc;

    /** 1 启用 / 0 停用（C-4.1-03 仅停用，禁止物理删除） */
    private String status;

    /** 记账本位币，ISO 4217 */
    private String bookkeepingCurrency;

    private String regPlace;

    /** 会计日历：NATURAL 自然年制 / APRIL 4月制 / JULY 7月制 / WEEK 52-53周制 */
    private String fiscalCalendarType;

    /** IANA 时区，如 Asia/Shanghai */
    private String defaultTimezone;

    private Integer currencyDecimals;

    /** 本地化合规字段包：CN / JP / EU，按注册地加载 */
    private String l10nPack;

    /** 本地税号：中国纳税人识别号 / 日本登录番号 / 欧盟 VAT ID */
    private String localTaxNo;

    private String legalRepresentative;

    private BigDecimal registeredCapital;

    private String regAddress;

    private String bankName;

    /** @deprecated 退役字段：关联中心改由成本中心/利润中心模块按 legal_entity_id 实时反查展示（列保留但读写全停，待确认后删除） */
    @Deprecated
    private String costCenterCode;

    /** @deprecated 退役字段：同上，派生展示见 costCenterCode */
    @Deprecated
    private String profitCenterCode;
}
