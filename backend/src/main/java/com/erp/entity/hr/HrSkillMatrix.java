package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 人员技能矩阵（skill-matrix，UK=EMP_ID+SKILL_CODE，CERT/TRAIN 双来源按来源隔离失效）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_skill_matrix")
public class HrSkillMatrix extends BaseEntity {

    public static final String SRC_CERT = "CERT";
    public static final String SRC_TRAIN = "TRAIN";

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_EXPIRED = "EXPIRED";

    private String empId;
    private String skillCode;
    private String skillLevel;
    /** CERT/TRAIN（last-writer-wins） */
    private String source;
    /** 来源单据（证书ID/计划ID） */
    private String sourceDoc;
    private String status;
}
