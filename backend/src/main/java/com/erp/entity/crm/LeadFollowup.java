package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 线索跟进记录（FR-4.8-1-4：仅可追加，不可删除或修改）。
 * 服务层不提供 update/delete 入口，写入后只读。
 */
@Getter
@Setter
@TableName("erp_crm_lead_followup")
public class LeadFollowup extends BaseEntity {

    /** 跟进方式 CALL/VISIT/DEMO/PROPOSAL/WECHAT/OTHER */
    public static final String[] TYPES = {"CALL", "VISIT", "DEMO", "PROPOSAL", "WECHAT", "OTHER"};

    private String leadId;
    private String followType;
    private String content;
    private LocalDate nextPlan;
    private String operatorId;
    private String operatorName;
    private LocalDateTime followAt;
}
