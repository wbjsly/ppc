package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 成果参与人员工时快照（add-ip-management，仲裁证据链按人可查）。 */
@Getter
@Setter
@TableName("erp_pm_achievement_member")
public class PmAchievementMember extends BaseEntity {

    private String achieveId;
    private String empId;
    private String empName;
    /** 工时小时数快照（后续工时变动不影响） */
    private java.math.BigDecimal hours;
}
