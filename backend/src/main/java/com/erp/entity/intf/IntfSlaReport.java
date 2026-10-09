package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 月度 SLA 报告（SOP-5.5-D：生成→审核→归档→发布，C-0-01 归档前禁止发布）。 */
@Getter
@Setter
@TableName("erp_intf_sla_report")
public class IntfSlaReport extends BaseEntity {

    public static final String ST_GENERATED = "GENERATED";
    public static final String ST_REVIEWED = "REVIEWED";
    public static final String ST_ARCHIVED = "ARCHIVED";
    public static final String ST_PUBLISHED = "PUBLISHED";

    private String monthTag;
    private String title;
    private String status;
    private String version;
    private String metricsJson;
    @com.baomidou.mybatisplus.annotation.TableField("UNREACHED_JSON")
    private String unreachJson;
    private String generatedBy;
    private LocalDateTime generatedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewOpinion;
    private String archivedBy;
    private LocalDateTime archivedAt;
    private LocalDateTime publishAt;
    private String publishScope;
    private String remark;
}
