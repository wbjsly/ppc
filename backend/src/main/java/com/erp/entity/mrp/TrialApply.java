package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 试产申请单（change add-trial-production-management，spec trial-order / 00-erp-spec 4.5-8 FR-4.5-8-1；迁移 125）。
 * TA-YYYYMMDD-NNN 编号；DRAFT → PENDING → APPROVED / REJECTED(回 DRAFT) / CONVERTED（1:1 转开 TP- 工单）。
 * 阶段（EVT/DVT/PVT）/数量/验证目标必填（缺失或空白 422，FR-4.5-8-1 异常「退回补充」落为提交校验）。
 */
@Getter
@Setter
@TableName("erp_mrp_trial_apply")
public class TrialApply extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_CONVERTED = "CONVERTED";

    public static final String EVT = "EVT";
    public static final String DVT = "DVT";
    public static final String PVT = "PVT";

    /** 申请单号 TA-YYYYMMDD-NNN（冲突自动递增） */
    private String trialNo;
    /** 试产阶段 EVT/DVT/PVT（缺失 422） */
    private String trialStage;
    /** 产品编码（自制件 MAKE 已发布启用） */
    private String productCode;
    /** 产品名称（冗余展示） */
    private String productName;
    /** 试产数量（>0） */
    private BigDecimal qty;
    /** 验证目标（空白 422 阻断提交） */
    private String goal;
    /** 状态机（白名单 CAS 迁移） */
    private String status;
    /** 转开试产工单 ID（1:1） */
    private String moId;
    /** 转开试产工单号 TP-（重复转开 422 锚点） */
    private String moNo;
    /** TrialApply 审批实例 ID */
    private String approvalId;
    /** 最近驳回意见（≥2 字） */
    private String rejectReason;

    private String submitBy;
    private LocalDateTime submitAt;
    private String approveBy;
    private LocalDateTime approveAt;
    private String convertBy;
    private LocalDateTime convertAt;
}
