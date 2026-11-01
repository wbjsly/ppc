package com.erp.entity.ops;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运维变更单（18.1）：申请→审批→执行→回滚→关闭。
 */
@Data
@TableName("erp_ops_change_ticket")
public class OpsChangeTicket implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String T_NORMAL = "NORMAL";
    public static final String T_EMERGENCY_FIX = "EMERGENCY_FIX";
    public static final String T_GRAY = "GRAY";

    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_EXECUTING = "EXECUTING";
    public static final String ST_ROLLED_BACK = "ROLLED_BACK";
    public static final String ST_CLOSED = "CLOSED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String ticketNo;

    private String title;

    /** NORMAL/EMERGENCY_FIX/GRAY */
    private String changeType;

    private String affectScope;

    private Integer estimateMin;

    private Integer rollbackMin;

    /** 三件套：script/drillAt/drillValid/estimateMin */
    private String rollbackPlanJson;

    /** SUBMITTED/APPROVED/EXECUTING/ROLLED_BACK/CLOSED */
    private String status;

    private String inWindow;

    private String windowCheckNote;

    private String accountingGuard;

    private String rollbackReason;

    private String rollbackRef;

    private String executor;

    private BigDecimal grayErrorRate;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}
