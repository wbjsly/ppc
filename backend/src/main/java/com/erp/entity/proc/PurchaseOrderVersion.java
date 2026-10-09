package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * PO 版本快照（change add-framework-agreement-order，design D8 / task 8.2，spec purchase-order）。
 * 记录 VERSION_NO=k 的行存放"版本 k 的头+行 JSON 快照"（该次变更发生前的状态），
 * 变更发生后 CURR_VERSION = k+1。CHG_TYPE: INIT/QTY/DATE/PRICE/LINE_CANCEL/LINE_ADD/ROLLBACK。
 * 变更分级审批（035 列）：REQ_ROLE 采购经理/采购总监（>PRICE_TOLERANCE 升级，C-4.2-07）。
 */
@Data
@TableName("erp_proc_po_version")
public class PurchaseOrderVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String poId;

    private Integer versionNo;

    private String chgType;

    private String chgReason;

    /** 头 + 行完整 JSON 快照（旧版本只读凭据，回滚数据源） */
    private String snapshotJson;

    /** NONE / PENDING / APPROVED / REJECTED */
    private String approvalStatus;

    /** 变更审批要求角色（金额增加时）：PURCHASE_MANAGER / PURCHASE_DIRECTOR */
    private String reqRole;

    /** 升级原因（超容差价差说明，C-4.2-07 / BR-4.2-42） */
    private String reqReason;

    private String reqApprovedBy;

    private LocalDateTime reqApprovedDate;

    private String createBy;

    private LocalDateTime createDate;
}
