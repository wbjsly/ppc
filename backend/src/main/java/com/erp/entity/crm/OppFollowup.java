package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商机跟进记录（与线索侧同规约：仅追加，不改不删）。
 * SYSTEM 类型用于转化闸口不可引用原因等自动回写（BR-4.3-07 追踪记录）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_crm_opp_followup")
public class OppFollowup extends BaseEntity {

    /** 合法跟进方式（含 SYSTEM 自动记录） */
    public static final String[] TYPES = {"CALL", "VISIT", "DEMO", "PROPOSAL", "WECHAT", "SYSTEM", "OTHER"};

    private String oppId;
    private String followType;
    private String content;
    private LocalDate nextPlan;
    private String operatorId;
    private String operatorName;
    private LocalDateTime followAt;
}
