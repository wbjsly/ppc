package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 线索池（FR-4.8-1-3 D 级入池待指派；FR-4.8-1-4 30 天未跟进回收留痕）。
 */
@Getter
@Setter
@TableName("erp_crm_lead_pool")
public class LeadPool extends BaseEntity {

    /** 入池原因 D_GRADE D 级 / RECYCLE 超期回收 / MANUAL 手工 */
    public static final String RS_D_GRADE = "D_GRADE";
    public static final String RS_RECYCLE = "RECYCLE";
    public static final String RS_MANUAL = "MANUAL";

    public static final String ST_PENDING = "PENDING";
    public static final String ST_ASSIGNED = "ASSIGNED";

    private String leadId;
    private String reason;
    private String gradeAtEntry;
    private LocalDateTime pooledAt;
    private String status;
    private String assignedTo;
    private String assignedBy;
    private LocalDateTime assignedAt;
    private String remark;
}
