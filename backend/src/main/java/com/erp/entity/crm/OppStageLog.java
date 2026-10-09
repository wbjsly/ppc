package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商机阶段推进日志（FR-4.8-1-6：每次转换生成审批任务）。
 * PENDING 期间商机停在原阶段；审批通过由 OppStageCallback 同事务跃迁阶段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_crm_opp_stage_log")
public class OppStageLog extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    private String oppId;
    private String fromStage;
    private String toStage;
    /** 本阶段概率 %（必填） */
    private Integer probability;
    private String nextAction;
    private LocalDate nextActionDate;

    private String status;
    private String applyBy;
    private LocalDateTime applyAt;
    private String approvalId;
    private String decideBy;
    private LocalDateTime decideAt;
    private String opinion;
}
