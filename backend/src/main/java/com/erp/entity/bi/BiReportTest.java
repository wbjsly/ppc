package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 报表模板测试报告（add-report-template-console design D5，
 * spec report-template-management「模板测试三项报告」）。
 * 三项 = 准确性样本核对 / 性能阈值 / 权限行数对比；
 * SQL_HASH 变更作废锚——模板元数据变更置 VALID=0 强制重测。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_bi_report_test")
public class BiReportTest extends BaseEntity {

    public static final String ST_PASS = "PASS";
    public static final String ST_FAIL = "FAIL";

    private String templateId;
    /** SQL+列+指标 元数据 SHA-256 前16位（变更作废锚） */
    private String sqlHash;
    private Long perfMs;
    private String perfLevel;
    private String perfOk;
    private Long rowCount;
    /** 前10行样本（准确性人工核对） */
    private String sampleJson;
    private Long permRowCount;
    private String permOk;
    private String accuracyOk;
    private String status;
    /** 有效标记：模板变更置 0 作废（强制重测） */
    private String valid;
    private String execBy;
    private LocalDateTime execAt;
}
