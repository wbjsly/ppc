package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** CPFR 协同协议（spec cpfr-collaboration R1：5 参数 + 启用管控 BR-4.9-31/34）。 */
@Getter
@Setter
@TableName("erp_scm_cpfr_agreement")
public class CpfrAgreement extends BaseEntity {

    public static final String ST_INIT = "未启用";
    public static final String ST_ACTIVE = "已启用";
    public static final String ST_PAUSED = "已暂停";
    public static final String ST_REVIEW = "待复盘";

    public static final String REASON_EXPIRE = "到期";
    public static final String REASON_ACCURACY = "准确率异常";
    public static final String REASON_MANUAL = "手工停用";

    private String supplierId;
    private String supplierName;
    /** WEEK / MONTH */
    private String cycleType;
    /** 预测展望期（周） */
    private Integer horizonWeeks;
    /** 冻结期周数 */
    private Integer freezeWeeks;
    /** 弹性区间起始周（含） */
    private Integer elasticStartWeek;
    /** 弹性区间结束周（含） */
    private Integer elasticEndWeek;
    /** 弹性区间允许调整比例（%） */
    private BigDecimal elasticPct;
    /** 分歧阈值（%，默认 10） */
    private BigDecimal deviationThresholdPct;
    /** 未启用/已启用/已暂停/待复盘 */
    private String status;
    /** 到期/准确率异常/手工停用 */
    private String pauseReason;
    private LocalDate expireDate;
    /** 最近推送周期（默认采纳取上轮量） */
    private String lastPeriodTag;
}
