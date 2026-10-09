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
    private String filePath;
    private String watermark;
    private String reqId;
    private Boolean notify;
    private LocalDateTime expireAt;
    private String errorMsg;
}
