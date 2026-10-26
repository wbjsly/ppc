package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 员工主档（全系统 SSoT，§4.7；BR-07 工号/身份证锁定，BR-09 身份证唯一）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_employee")
public class HrEmployee extends BaseEntity {

    public static final String ST_PROBATION = "PROBATION";
    public static final String ST_REGULAR = "REGULAR";
    public static final String ST_RESIGNED = "RESIGNED";

    /** 工号 EMP+yyyy+4位流水（生成后锁定） */
    private String empNo;
    private String name;
    /** 身份证号（全库唯一） */
    private String idCardNo;
    private String gender;
    private LocalDate birthDate;
    private String phone;
    private String address;
    private String emergencyContact;
    private String bankCardNo;
    private String education;
    /** 工作经历 JSON */
    private String workHistoryJson;
    private String deptId;
    private String posId;
    /** 直接汇报人（指向 hr_employee.ID，add-org-management 两层汇报模型） */
    private String reportToEmpId;
    private String costCenter;
    /** PROBATION / REGULAR / RESIGNED */
    private String empStatus;
    private LocalDate hireDate;
    private LocalDate probationEnd;
    private String contractNo;
    private LocalDate contractStart;
    private LocalDate contractEnd;
    /** 社保缴纳地（留桩） */
    private String socialPlace;
    /** 所属法人主体 LE_CODE（缺省主法人，外派生效写回，add-employee-secondment） */
    private String legalEntity;
    private String socialType;
    private LocalDate socialDate;
    /** 联动系统账号 ID */
    private String userId;
    /** 二次入职：原主档 ID */
    private String rejoinOf;
    private LocalDate resignedDate;
    /** 月薪基数（加班工资/病事假日工资统一计算基准，add-attendance-management） */
    private java.math.BigDecimal baseSalary;
    /** 考勤方案 STANDARD/COMPREHENSIVE/IRREGULAR */
    private String attendScheme;
}
