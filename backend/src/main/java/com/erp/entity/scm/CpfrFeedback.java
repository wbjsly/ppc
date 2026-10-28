package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** CPFR 供给能力反馈（spec cpfr-collaboration R3，BR-4.9-33）。 */
@Getter
@Setter
@TableName("erp_scm_cpfr_feedback")
public class CpfrFeedback extends BaseEntity {

    public static final String ST_PENDING = "待反馈";
    public static final String ST_DONE = "已反馈";
    public static final String ST_REMINDED = "已催办";
    public static final String ST_ESCALATED = "已升级";
    public static final String ST_DEFAULT = "默认采纳";

    public static final String SRC_PORTAL = "PORTAL";
    public static final String SRC_DEFAULT_ROLL = "DEFAULT_ROLL";

    private String agreementId;
    private String periodTag;
    private Integer weekOffset;
    private String itemCode;
    /** 需求量（取预测行，比对基准） */
    private BigDecimal demandQty;
    /** 可供数量（默认采纳 = 上轮量） */
    private BigDecimal supplyQty;
    private String capacityNote;
    private String riskNote;
    /** PORTAL / DEFAULT_ROLL */
    private String feedbackSource;
    private String status;
    /** 反馈时间（记分卡 CPFR 反馈及时率取数） */
    private LocalDateTime submitAt;
    /** 周期开始时间（48h/72h 判定基准） */
    private LocalDateTime periodStartAt;
    /** 催办时间（幂等标记） */
    private LocalDateTime remindAt;
    /** 升级时间（幂等标记） */
    private LocalDateTime escalateAt;
    /** 升级接收角色 */
    private String escalateToRole;
}
