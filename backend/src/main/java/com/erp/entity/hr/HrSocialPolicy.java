package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 社保公积金属地政策（城市×年度×险种行式；未配置 L1 阻断核算）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_social_policy")
public class HrSocialPolicy extends BaseEntity {
    public static final String INS_PENSION = "PENSION";
    public static final String INS_MEDICAL = "MEDICAL";
    public static final String INS_UNEMPLOY = "UNEMPLOY";
    public static final String INS_INJURY = "INJURY";
    public static final String INS_MATERNITY = "MATERNITY";
    public static final String INS_PROVIDENT = "PROVIDENT";

    private String city;
    private Integer year;
    private String insType;
    /** 缴费基数下限（保底） */
    private BigDecimal baseMin;
    /** 缴费基数上限（封顶） */
    private BigDecimal baseMax;
    /** 单位比例（0.16=16%） */
    private BigDecimal empRate;
    /** 个人比例 */
    private BigDecimal eeRate;
}
