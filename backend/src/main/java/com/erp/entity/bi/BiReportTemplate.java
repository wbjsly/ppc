package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 报表模板（add-report-template-console design D1，spec report-template-management）。
 * 可执行元数据：指标引用 + 只读 SQL + 列定义；状态机
 * DRAFT → TESTING → PENDING_APPROVAL → PUBLISHED（REJECTED 可回退修改重提）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_bi_report_template")
public class BiReportTemplate extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_TESTING = "TESTING";
    public static final String ST_PENDING = "PENDING_APPROVAL";
    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_REJECTED = "REJECTED";

    /** 性能阈值档：标准报表 ≤10s */
    public static final String LEVEL_STANDARD = "STANDARD";
    /** 性能阈值档：复杂报表 ≤30s */
    public static final String LEVEL_COMPLEX = "COMPLEX";

    private String code;
    private String name;
    /** STANDARD / COMPLEX——性能阈值档（D5） */
    private String category;
    /** 只读查询 SQL（SELECT-only 白名单校验，D2） */
    private String sqlText;
    /** 列定义 JSON（渲染表头/类型/敏感列掩码标记） */
    private String columnsJson;
    /** 指标引用数组 [{metricKey, formula}]（口径 diffCheck 对象，D3） */
    private String metricsJson;
    private String status;
    /** 当前生效版本快照 ID（草稿不生效线版） */
    private String activeVersionId;
    private String apprInstanceId;
    private String remark;
}
