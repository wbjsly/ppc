package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 移动待办中心（13.7 聚合 + 30min 超时升级计时） */
@Data
@TableName("erp_iot_mobile_todo")
public class IotMobileTodo implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String TYPE_APPROVAL = "APPROVAL";
    public static final String TYPE_EXCEPTION = "EXCEPTION";
    public static final String TYPE_INSPECT = "INSPECT";
    public static final String TYPE_ESCALATE = "ESCALATE";
    public static final String TYPE_REPORT = "REPORT";
    public static final String TYPE_ALERT = "ALERT";

    public static final String ST_PENDING = "PENDING";
    public static final String ST_DONE = "DONE";
    public static final String ST_ESCALATED = "ESCALATED";
    public static final String ST_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** APPROVAL/EXCEPTION/INSPECT/ESCALATE/REPORT/ALERT */
    private String todoType;

    /** 原单类型（APPROVAL_FLOW/NCR/EAM_WO...） */
    private String refType;

    /** 原单 ID（处理回写用） */
    private String refId;

    private String title;

    private String detailJson;

    /** 当前处理人 */
    private String assignee;

    /** 30min 超时计时 */
    private LocalDateTime dueAt;

    private String escalatedTo;
    private LocalDateTime escalatedAt;

    private String status;

    private String handledBy;
    private LocalDateTime handledAt;
    private String handlingOpinion;

    /** 财务岗位签署要求 */
    private String needFinSign;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}
