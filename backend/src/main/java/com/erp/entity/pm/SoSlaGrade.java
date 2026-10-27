package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 服务工单定级映射（add-service-order，FR-4.8-9-2 后台可配）。 */
@Getter
@Setter
@TableName("erp_pm_service_sla_grade")
public class SoSlaGrade extends BaseEntity {

    /** 投诉类型，* = 通配（精确行优先） */
    private String complaintType;
    /** PRODUCTION|CORE|NORMAL */
    private String impactLevel;
    /** 定级结果 1/2/3 */
    private Integer level;
    /** 响应时限小时 */
    private Integer respHours;
    /** 解决时限小时 */
    private Integer resolveHours;
    private String enabled;
}
