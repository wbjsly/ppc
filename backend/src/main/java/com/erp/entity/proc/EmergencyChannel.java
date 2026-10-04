package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 紧急采购通道台账（2.1.3，表 erp_proc_emergency_channel，L1321）。
 * 按登录账号唯一；无行 = 已开通（隐式 OPEN）；BLOCKED 阻断新申请发起。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_proc_emergency_channel")
public class EmergencyChannel extends BaseEntity {

    /** 登录账号（唯一） */
    private String account;

    /** OPEN / BLOCKED */
    private String status;

    private String blockReason;
    private LocalDateTime blockDate;
    private String blockEaNo;

    // ---- 总监复核恢复留痕 ----
    private String reviewBy;
    private LocalDateTime reviewDate;
    private String reviewNote;
}
