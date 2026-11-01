package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 后台导出与查询审计一体（spec bi-query-governance，design D6）。 */
@Getter
@Setter
@TableName("erp_bi_export_task")
public class BiExportTask extends BaseEntity {

    public static final String TYPE_QUERY = "QUERY";
    public static final String TYPE_EXPORT = "EXPORT";

    public static final String ST_QUEUED = "QUEUED";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_DONE = "DONE";
    public static final String ST_FAILED = "FAILED";

    private String taskType;
    private String taskNo;
    private String apiPath;
    private String paramsJson;
    private String maskCols;
    private String userName;
    private Long rowCount;
    private Long estRows;
    private String status;
    /** 超量审批 PENDING / APPROVED / REJECTED（C-4.10-05） */
    private String approvalStatus;
    /** 超量导出用途说明（≥10字，FR-4.10-4-2） */
    private String purpose;
    /** 优先级 SMALL≤1000 / NORMAL≤1万 / LOW>1万（FR-4.10-4-3） */
    private String priority;
    private String filePath;
    private String watermark;
    private String reqId;
    private Boolean notify;
    private LocalDateTime expireAt;
    /** 生命周期清理时间（FR-4.10-4-6） */
    private LocalDateTime cleanAt;
    /** 删除前 24h 提醒时间 */
    private LocalDateTime remindAt;
    private String errorMsg;
}
