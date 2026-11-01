package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 即席查询任务（add-adhoc-query-console，spec bi-adhoc-query）。
 * 同步执行也落行（STATUS 直接 DONE，RESULT_JSON 空）——统一计数与留痕口径；
 * 预检阻断落 REJECTED 行（C-4.10-10）。
 */
@Getter
@Setter
@TableName("erp_bi_adhoc_task")
public class AdhocTask extends BaseEntity {

    public static final String ST_QUEUED = "QUEUED";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_DONE = "DONE";
    public static final String ST_FAILED = "FAILED";
    public static final String ST_TIMEOUT = "TIMEOUT";
    public static final String ST_REJECTED = "REJECTED";

    public static final String PRI_SMALL = "SMALL";
    public static final String PRI_NORMAL = "NORMAL";
    public static final String PRI_LOW = "LOW";

    private String taskNo;
    /** 任务属主（执行时行级权限身份） */
    private String userName;
    private String deptName;
    /** 数据集=模板 ID */
    private String templateId;
    /** 结构化查询定义快照（QUERY_JSON） */
    private String queryJson;
    private String status;
    private String priority;
    private Long estRows;
    private Long estMs;
    private Long actualRows;
    private Long execMs;
    /** 结果集（≤EXPORT_ROW_LIMIT 行；同步路径为空） */
    private String resultJson;
    private Boolean truncated;
    private String errorMsg;
    private Boolean notifyFlag;
    private String rejectReason;
}
