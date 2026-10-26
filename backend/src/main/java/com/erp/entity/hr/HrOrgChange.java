package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 组织调整单（M10-2：合并/主管变更/带宽变更统一走 ApprovalEngine）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_org_change")
public class HrOrgChange extends BaseEntity {

    public static final String TYPE_MERGE = "MERGE";
    public static final String TYPE_DEPT_MANAGER = "DEPT_MANAGER";
    public static final String TYPE_GRADE_BAND = "GRADE_BAND";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_EFFECTED = "EFFECTED";
    public static final String ST_REJECTED = "REJECTED";

    /** 调整单号 OC+yyyyMMdd+4 位流水 */
    private String chgNo;
    /** MERGE / DEPT_MANAGER / GRADE_BAND */
    private String chgType;
    /** 源（被合并部门/被变更对象） */
    private String sourceId;
    /** 目标（承接部门） */
    private String targetId;
    /** 前后快照 JSON（before/after + 执行结果计数） */
    private String payloadJson;
    /** 拟生效日 */
    private LocalDate effectiveDate;
    /** DRAFT→APPROVING→EFFECTED/REJECTED */
    private String status;
    /** 审批实例锚 */
    private String apprId;
    private LocalDateTime executedAt;
}
