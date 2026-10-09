package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 效期质量评估单（4.10.3，spec expiry-management 需求④；迁移 113）。
 * 手工发起（非锁定批次 422、重复未关闭 422）→ 判定三分支：
 * SCRAP 报废（生成报废单+审批）/ RELEASE 让步放行（审批+豁免）/ FREEZE 转质量冻结（即刻 CLOSED）。
 * 状态：PENDING_EVAL 待判定 → PENDING_APPR 审批中 → CLOSED。
 */
@Getter
@Setter
@TableName("erp_inv_expiry_eval")
public class ExpiryEval extends BaseEntity {

    public static final String ST_PENDING_EVAL = "PENDING_EVAL";
    public static final String ST_PENDING_APPR = "PENDING_APPR";
    public static final String ST_CLOSED = "CLOSED";

    public static final String C_SCRAP = "SCRAP";
    public static final String C_RELEASE = "RELEASE";
    public static final String C_FREEZE = "FREEZE";

    /** 审批底座 BIZ_TYPE（同人防闭环由底座 rejectSelfSign 拦截） */
    public static final String BIZ_TYPE = "ExpiryEval";

    /** 评估单号 EV+yyMMdd+4位日流水 */
    private String evalNo;

    private String itemCode;

    private String itemName;

    private String batchNo;

    /** 发起时批次所在仓库（展示用，可空） */
    private String warehouseCode;

    /** 发起时锁定来源快照 AUTO/MANUAL */
    private String lockSource;

    /** PENDING_EVAL / PENDING_APPR / CLOSED */
    private String status;

    /** SCRAP / RELEASE / FREEZE */
    private String conclusion;

    /** 评估说明（必填） */
    private String evalNote;

    /** 放行有效期（RELEASE 必填且 > 今日） */
    private LocalDate releaseUntil;

    /** 关联报废单号（SCRAP 分支） */
    private String scrapDocNo;

    /** 关联冻结单号（FREEZE 分支） */
    private String freezeNo;

    /** 审批实例 ID（BIZ_TYPE=ExpiryEval） */
    private String apprId;

    /** 驳回意见（回 PENDING_EVAL 留痕） */
    private String apprOpinion;

    /** 判定人（签署同人拦截依据） */
    private String evalBy;

    private LocalDateTime closeAt;

    private String remark;
}
