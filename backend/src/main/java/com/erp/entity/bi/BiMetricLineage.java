package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 指标血缘四层关系快照（add-metric-dict-console design D4，
 * spec bi-metric-lineage）：上游表 ← 指标 ← 衍生指标 ← 引用报表。
 * 快照与口径版本绑定，生成后无 UPDATE 入口（审计只增，历史可回溯）。
 */
@Getter
@Setter
@TableName("erp_bi_metric_lineage")
public class BiMetricLineage extends BaseEntity {

    public static final String NODE_TABLE = "TABLE";
    public static final String NODE_METRIC = "METRIC";
    public static final String NODE_REPORT = "REPORT";

    private String metricKey;
    private Integer lineageVersion;
    private String nodeType;
    private String nodeId;
    private String nodeName;
    /** 高风险标注（对外披露/驾驶舱核心报表） */
    private String highRisk;
    private LocalDateTime snapshotAt;
    private String remark;
}
