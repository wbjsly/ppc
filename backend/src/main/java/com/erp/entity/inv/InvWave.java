package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 拣货波次头（4.8.1，spec wave-management 波次状态机）：
 * CREATED → ALLOCATED → PICKING → SORTING → STAGING → SHIPPING → CLOSED，分支 CANCELLED（未开拣、原因必填）。
 * 状态推进事件驱动（design D5），无轮询调度。
 */
@Getter
@Setter
@TableName("erp_inv_wave")
public class InvWave extends BaseEntity {

    public static final String ST_CREATED = "CREATED";
    public static final String ST_ALLOCATED = "ALLOCATED";
    public static final String ST_PICKING = "PICKING";
    public static final String ST_SORTING = "SORTING";
    public static final String ST_STAGING = "STAGING";
    public static final String ST_SHIPPING = "SHIPPING";
    public static final String ST_CLOSED = "CLOSED";
    public static final String ST_CANCELLED = "CANCELLED";
    /**
     * 冻结挂起（freeze-management ADDED 需求①）：CREATED/ALLOCATED/PICKING/SORTING
     * 可入；STAGING/SHIPPING 不暂停（过账引擎批次预算兜底）。解冻不自动恢复。
     */
    public static final String ST_PAUSED = "PAUSED";

    /** 波次号 WV+yyyyMMdd+流水 */
    private String waveNo;

    /** 聚类键值（线路/承运商/客户标识快照） */
    private String clusterKey;

    /** 聚类主键类型 ROUTE/CARRIER/CUSTOMER */
    private String clusterType;

    private String status;

    /** 暂停来源冻结单号（迁移 112；解冻 RELEASED 后方可恢复） */
    private String pauseFreezeNo;

    /** 暂停前状态（恢复时 CAS 回该状态） */
    private String pauseFromStatus;

    private Integer docCount;

    private LocalDateTime allocAt;
    private LocalDateTime pickAt;
    private LocalDateTime sortAt;
    private LocalDateTime loadAt;
    private LocalDateTime closeAt;

    private String cancelReason;

    private String remark;
}
