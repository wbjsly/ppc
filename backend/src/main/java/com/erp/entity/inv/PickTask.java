package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 拣货任务（4.7.1，spec picking-review 拣货任务生成与生命周期）：
 * 单据级任务，4.6.3 确认后自动生成（SRC UK 幂等）；主管可改派/作废。
 * 状态机 CREATED→PICKING→PICKED→REVIEWING→DONE→COMPLETED，分支 DIFF_PENDING/QUALITY_PENDING/CANCELLED。
 */
@Getter
@Setter
@TableName("erp_pick_task")
public class PickTask extends BaseEntity {

    public static final String ST_CREATED = "CREATED";
    public static final String ST_PICKING = "PICKING";
    public static final String ST_PICKED = "PICKED";
    public static final String ST_REVIEWING = "REVIEWING";
    public static final String ST_DONE = "DONE";
    public static final String ST_COMPLETED = "COMPLETED";
    public static final String ST_DIFF_PENDING = "DIFF_PENDING";
    public static final String ST_QUALITY_PENDING = "QUALITY_PENDING";
    public static final String ST_CANCELLED = "CANCELLED";
    /** 冻结挂起（freeze-management ADDED 需求①：解冻不自动恢复，主管手动恢复） */
    public static final String ST_PAUSED = "PAUSED";

    /** 任务号 PT+yyyyMMdd+流水 */
    private String taskNo;

    /** 来源出库类型：SALES_OUT / MATERIAL_OUT / TRANSFER_OUT / SCRAP_OUT */
    private String srcType;

    private String srcDocId;

    private String srcDocNo;

    private String status;

    /** 暂停来源冻结单号（迁移 112；解冻 RELEASED 后方可恢复） */
    private String pauseFreezeNo;

    /** 暂停前状态（恢复时 CAS 回该状态） */
    private String pauseFromStatus;

    /** 拣货员 */
    private String picker;

    /** 复核员 */
    private String reviewer;

    /** 改派前拣货员（留痕） */
    private String prevPicker;

    private String assignBy;

    private LocalDateTime assignAt;

    private String cancelReason;

    private LocalDateTime pickAt;

    private LocalDateTime reviewAt;

    private LocalDateTime completeAt;

    private String remark;
}
