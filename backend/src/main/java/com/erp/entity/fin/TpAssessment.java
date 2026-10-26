package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 特别纳税调整风险自评（留痕不阻断）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_tp_assessment")
public class TpAssessment extends BaseEntity {

    public static final String CONCLUSION_CLEAN = "CLEAN";
    public static final String CONCLUSION_IMPROVE = "IMPROVE";

    /** 自评单号 TPA+年度+流水 */
    private String assessNo;
    /** 自评年度 yyyy（同年度一条） */
    private String assessYear;
    /** 勾选风险项 JSON */
    private String riskItemsJson;
    /** CLEAN 未发现 / IMPROVE 有待改进 */
    private String conclusion;
    private String assessBy;
    private LocalDateTime assessAt;
}
