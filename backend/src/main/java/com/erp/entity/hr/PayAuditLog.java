package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 薪酬审计日志（BR-4.7-15 L5：查看/导出/核算/审批/更正/工资条访问，只插不改）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_audit_log")
public class PayAuditLog extends BaseEntity {
    public static final String ACT_VIEW = "VIEW";
    public static final String ACT_EXPORT = "EXPORT";
    public static final String ACT_CALC = "CALC";
    public static final String ACT_APPROVE = "APPROVE";
    public static final String ACT_CORRECT = "CORRECT";
    public static final String ACT_SLIP_VIEW = "SLIP_VIEW";

    private String actor;
    private String action;
    private String targetType;
    private String targetId;
    private String period;
    private String detail;
    private LocalDateTime opAt;
}
