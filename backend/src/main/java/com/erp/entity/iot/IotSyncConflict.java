package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 离线同步冲突与差异记录（BL-4.11-01 字典序裁决，C-4.11-01/12） */
@Data
@TableName("erp_iot_sync_conflict")
public class IotSyncConflict implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数量自动合并累加 */
    public static final String TYPE_MERGE = "MERGE";
    /** 状态类最新优先 */
    public static final String TYPE_LATEST = "LATEST";
    /** 超容差差异待处理（L3） */
    public static final String TYPE_DIFF = "DIFF";
    /** 人工复核 */
    public static final String TYPE_REVIEW = "REVIEW";

    public static final String ST_OPEN = "OPEN";
    public static final String ST_RESOLVED = "RESOLVED";
    public static final String ST_NEED_REVIEW = "NEED_REVIEW";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String queueId;

    /** MERGE/LATEST/DIFF/REVIEW */
    private String conflictType;

    private String fieldPath;

    private String beforeSnapshot;

    private String afterSnapshot;

    /** 字典序裁决键 VER_NO>CLIENT_TS>IDEMPOTENT_KEY */
    private String dictSeqKey;

    /** 财务数据 L2 强制审批 */
    private String needFinApproval;

    private String status;

    private String resolvedBy;

    private LocalDateTime resolvedAt;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}
