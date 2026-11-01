package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 数据质量校验报告（add-data-extract-console design D1/D4，spec bi-quality-check）。
 * 规则名称/类型/级别快照落报告，保留 ≥180 天（AUDIT_RETENTION_DAYS）。
 */
@Getter
@Setter
@TableName("erp_bi_quality_result")
public class BiQualityResult extends BaseEntity {

    public static final String CONC_PASS = "PASS";
    public static final String CONC_FAIL = "FAIL";

    /** 批次号（手动校验为空） */
    private String batchNo;
    private String ruleId;
    private String ruleName;
    private String ruleType;
    private String targetDesc;
    private String expected;
    private String actual;
    private String conclusion;
    private String severeLevel;
    private String execBy;
    private LocalDateTime execAt;
    private String errorMsg;
}
