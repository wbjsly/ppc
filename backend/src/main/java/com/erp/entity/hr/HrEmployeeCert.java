package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 员工资格认证台账（BR-4.7-06 到期翻转与排班门禁判定源）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_employee_cert")
public class HrEmployeeCert extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_VALID = "VALID";
    public static final String ST_REVIEW_DUE = "REVIEW_DUE";
    public static final String ST_EXPIRED = "EXPIRED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_ARCHIVED = "ARCHIVED";

    private String empId;
    private String empName;
    private String empNo;
    private String cateId;
    /** 冗余快照（类别改名不影响历史） */
    private String cateName;
    private String certNo;
    private String issueOrg;
    private LocalDate validFrom;
    /** 到期日（翻转与门禁判定基准） */
    private LocalDate validTo;
    private String status;
    private String reviewOpinion;
    private String auditBy;
    private LocalDateTime auditAt;
}
