package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 月度报表 SLA 报告（add-bi-sla-report，spec bi-sla-report，4.10 流程五）。
 * 状态机 DRAFT→PENDING_APPROVAL→APPROVED→ARCHIVED→PUBLISHED；
 * 归档=版本锁定只读，修改走 VERSION+1 新行；与 16.9 erp_sla_report 完全隔离。
 */
@Getter
@Setter
@TableName("erp_bi_sla_report")
public class BiSlaReport extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING_APPROVAL";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_ARCHIVED = "ARCHIVED";
    public static final String ST_PUBLISHED = "PUBLISHED";

    private String period;
    private Integer version;
    private String metricsJson;
    private String rootCauseJson;
    private String improveJson;
    private String status;
    private String approvalId;
    private String rejectReason;
    private String archiveBy;
    private LocalDateTime archiveAt;
    private String publishBy;
    private LocalDateTime publishAt;
    private String publishJson;
}
