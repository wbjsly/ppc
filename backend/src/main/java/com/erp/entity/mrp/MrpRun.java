package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * MRP 运行头（change add-mrp-demand-planning，spec mrp-demand-planning / 00-erp-spec 4.5-2；迁移 119）。
 * RUN_NO 流水 RUN-YYYYMMDD-NNN；RUN_STATUS 兼作并发互斥位（同刻至多一个 RUNNING，design D2/D8）；
 * 范围三选一（FULL/CATEGORY/GROUP，BR-4.5-11，产品族以分类代理、计划员维度延后 proposal D5）。
 */
@Getter
@Setter
@TableName("erp_mrp_run")
public class MrpRun extends BaseEntity {

    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_DONE = "DONE";
    public static final String ST_FAILED = "FAILED";

    public static final String SCOPE_FULL = "FULL";
    public static final String SCOPE_CATEGORY = "CATEGORY";
    public static final String SCOPE_GROUP = "GROUP";

    /** 运行流水号 RUN-YYYYMMDD-NNN */
    private String runNo;
    /** 范围 FULL/CATEGORY/GROUP */
    private String scopeType;
    /** 范围值（分类码/物料组；FULL 为空） */
    private String scopeValue;
    /** 运行状态（互斥位） */
    private String runStatus;
    private String runBy;
    private LocalDateTime runAt;
    private LocalDateTime finishAt;
    /** 扫描物料数 */
    private Integer statScanned;
    /** 建议行数（PURCHASE+PRODUCTION） */
    private Integer statSuggested;
    /** 异常行数（EXCESS+OVERDUE） */
    private Integer statException;
    /** 失败原因（FAILED 时） */
    private String failMsg;
}
