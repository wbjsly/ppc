package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 补卡申请（BR-4.7-10：员工提交→主管确认→HR 审核→写 MANUAL 打卡+重识别）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_makeup_punch")
public class HrMakeupPunch extends BaseEntity {
    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    private String empId;
    private LocalDate makeupDate;
    /** IN/OUT */
    private String dir;
    private String reason;
    private String status;
    private String mgrOpinion;
    private String mgrBy;
    private String hrOpinion;
    private String hrBy;
    private String apprId;
    /** 通过后生成的打卡记录 ID */
    private String punchId;
}
