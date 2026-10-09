package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 记分卡结果（月度不可变；修正版新行 + VERSION_TAG，原版保留）。 */
@Getter
@Setter
@TableName("erp_scm_scorecard_result")
public class ScmScorecardResult extends BaseEntity {

    public static final String ST_GENERATED = "GENERATED";
    public static final String ST_REVIEWED = "REVIEWED";
    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_SUSPENDED = "SUSPENDED";

    public static final String DATA_OK = "OK";
    /** 单指标缺失 */
    public static final String DATA_MISSING = "MISSING_DIM";
    /** 维度整体缺失暂缓发布 */
    public static final String DATA_INCOMPLETE = "INCOMPLETE";
    /** 计算异常转人工不输出等级 */
    public static final String DATA_ABNORMAL = "ABNORMAL";

    private String monthTag;
    private String supplierId;
    private String modelId;
    private Integer modelVersion;
    private BigDecimal qValue;
    private BigDecimal qScore;
    private BigDecimal dValue;
    private BigDecimal dScore;
    private BigDecimal cValue;
    private BigDecimal cScore;
    private BigDecimal rValue;
    private BigDecimal rScore;
    private BigDecimal totalScore;
    private String grade;
    private String dataStatus;
    private String versionTag;
    private String originId;
    private String status;
    private String sourceJson;
    private LocalDateTime calcAt;
    private String reviewBy;
    private LocalDateTime reviewAt;
    private LocalDateTime publishAt;
    private LocalDateTime appealDeadline;
    private String remark;
}
