package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * PO 交期协同流水（spec po-collaboration，design D3）：
 * 时间线与「协同异常工单」由流水行承载，不建独立工单表。
 */
@Getter
@Setter
@TableName("erp_proc_po_coop")
public class PoCoopLog extends BaseEntity {

    public static final String ACT_PUSHED = "PUSHED";
    public static final String ACT_REMIND = "REMIND";
    public static final String ACT_ESCALATE = "ESCALATE";
    public static final String ACT_CONFIRM = "CONFIRM";
    public static final String ACT_CHANGE_ACCEPTED = "CHANGE_ACCEPTED";
    public static final String ACT_LOCK = "LOCK";
    public static final String ACT_UNLOCK = "UNLOCK";

    public static final String SRC_PORTAL = "PORTAL";
    public static final String SRC_OFFLINE = "OFFLINE";
    public static final String SRC_SYSTEM = "SYSTEM";

    private String poId;
    private String poNo;
    private String supplierId;
    /** PUSHED/REMIND/ESCALATE/CONFIRM/CHANGE_ACCEPTED/LOCK/UNLOCK */
    private String actionType;
    /** CONFIRM/CHANGE_ACCEPTED 的承诺交期 */
    private LocalDate promiseDate;
    /** PORTAL 门户 / OFFLINE 代录 / SYSTEM 系统动作 */
    private String source;
    /** 动作操作人（门户=账号名） */
    private String operatorName;
    /** 动作明细 JSON */
    private String detail;
    private String remark;
    private LocalDateTime actionAt;
}
