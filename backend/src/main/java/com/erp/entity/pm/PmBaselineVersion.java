package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 项目基线版本（add-project-change，BR-4.8-31 永久只读）。
 * 每版存 erp_pm_budget_line 六列全量快照；历史版不提供 UPDATE 入口（服务层保证只读）。
 * 版本号：范围/预算/资源类变更 → 次版本 +1；涉合同 → 主版本 +1。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_baseline_version")
public class PmBaselineVersion extends BaseEntity {

    private String projectId;
    /** 如 V1.0 / V1.1（可读标签） */
    private String verNoLabel;
    /** 主版本（涉合同变更 +1） */
    private Integer majorVer;
    /** 次版本（范围/预算/资源类 +1） */
    private Integer minorVer;
    /** erp_pm_budget_line 六列全量快照 */
    private String snapshotJson;
    /** 该版本基线总额 */
    private BigDecimal baselineAmount;
    /** 1=当前生效版 */
    private String currentFlag;
    /** 来源变更单（NULL=立项初版） */
    private String changeId;
}
