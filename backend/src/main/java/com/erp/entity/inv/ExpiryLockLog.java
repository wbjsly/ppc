package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 效期锁定变更历史（4.10.2 台账，spec expiry-management 需求②；迁移 113）。
 * 仅 flag 实际变化时写入（扫描幂等零噪音）；人工锁定/评估放行在同事务追加。
 * 表无 DEL_FLAG，不继承 BaseEntity（append-only 流水，同 TENDER/PO_LINE 惯例）。
 */
@Data
@TableName("erp_inv_expiry_lock_log")
public class ExpiryLockLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 变更来源 */
    public static final String SRC_SCAN = "SCAN";
    public static final String SRC_MANUAL = "MANUAL";
    public static final String SRC_EVAL_RELEASE = "EVAL_RELEASE";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String batchNo;

    private String itemCode;

    /** 变更前标记 0/1 */
    private String fromFlag;

    /** 变更后标记 0/1 */
    private String toFlag;

    /** SCAN / MANUAL / EVAL_RELEASE */
    private String source;

    /** 操作人（扫描=system:scan） */
    private String operator;

    /** 原因（人工锁定必填/放行记录评估单号） */
    private String reason;

    private LocalDateTime changeAt;

    /** 变更日志工厂（扫描/人工锁定/评估放行三处共用，flag 变化才调用） */
    public static ExpiryLockLog of(InvBatch b, String fromFlag, String toFlag,
                                   String source, String operator, String reason) {
        ExpiryLockLog l = new ExpiryLockLog();
        l.setBatchNo(b.getBatchNo());
        l.setItemCode(b.getItemCode());
        l.setFromFlag(fromFlag == null ? "0" : fromFlag);
        l.setToFlag(toFlag);
        l.setSource(source);
        l.setOperator(operator);
        l.setReason(reason);
        l.setChangeAt(java.time.LocalDateTime.now());
        return l;
    }
}
