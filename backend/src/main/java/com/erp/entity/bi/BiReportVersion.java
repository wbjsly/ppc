package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 报表模板版本快照（add-report-template-console design D1，
 * spec report-template-management「发布快照与版本回退」，BR-4.10-07 双维度绑定）。
 * 发布即不可变；回退生成新版本号保留链（ROLLBACK_FROM 溯源）；归档只读（C-0-05）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_bi_report_version")
public class BiReportVersion extends BaseEntity {

    private String templateId;
    private Integer versionNo;
    /** 全量定义快照（SQL/列/指标引用，发布即不可变） */
    private String defJson;
    /** 口径版本绑定 [{metricKey, metricVersion}]（BR-4.10-07 双维度） */
    private String metricVersionsJson;
    /** 回退来源版本号（正常发布为 NULL） */
    private Integer rollbackFrom;
    private String publishBy;
    private LocalDateTime publishAt;
}
