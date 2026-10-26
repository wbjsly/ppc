package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 技改大修单（add-asset-rework，design D1：RWF 单号/原卡锚/判定与证据/特批续提/CIP 互锚/新卡锚/三凭证锚/状态机）。
 */
@Getter
@Setter
@TableName("erp_eam_rework_order")
public class EamReworkOrder extends BaseEntity {

    /** 技改单号 RWF+yyMM+4位流水 */
    @TableField("RWF_NO")
    private String rwfNo;

    /** 原卡锚 */
    @TableField("CARD_ID")
    private String cardId;

    /** 原卡号快照 */
    @TableField("CARD_NO")
    private String cardNo;

    /** 原资产名快照 */
    @TableField("ASSET_NAME")
    private String assetName;

    /** 分类快照（资本化下限取数） */
    @TableField("CATEGORY_CODE")
    private String categoryCode;

    @TableField("DEPT_NAME")
    private String deptName;

    /** 技改/大修内容 */
    @TableField("CONTENT")
    private String content;

    /** 技改预算（→CIP 项目预算） */
    @TableField("BUDGET_AMOUNT")
    private BigDecimal budgetAmount;

    /** 预期效果描述 */
    @TableField("EXPECTED_EFFECT")
    private String expectedEffect;

    /** 1=已提足卡，须财务经理复核后判定 */
    @TableField("FLOOR_REVIEW_FLAG")
    private String floorReviewFlag;

    /** 1=BR-38 防拆分命中，判定强制财务经理复核 */
    @TableField("FLOOR_MERGE_FLAG")
    private String floorMergeFlag;

    /** 资本化判定：CAP / EXPENSE / null 待判定 */
    @TableField("CAP_JUDGE")
    private String capJudge;

    /** 判定说明/争议裁定留痕 */
    @TableField("JUDGE_NOTE")
    private String judgeNote;

    /** 判定证据附件锚 */
    @TableField("JUDGE_EVIDENCE")
    private String judgeEvidence;

    @TableField("JUDGE_BY")
    private String judgeBy;

    @TableField("JUDGE_DATE")
    private LocalDateTime judgeDate;

    /** 1=特批续提（BR-39 财务经理特批，期间继续计提） */
    @TableField("CONTINUE_DEPR_FLAG")
    private String continueDeprFlag;

    /** 特批续提审批实例 */
    @TableField("CONTINUE_APPR_ID")
    private String continueApprId;

    /** 1:1 CIP 项目互锚（转在建时创建） */
    @TableField("CIP_PROJECT_ID")
    private String cipProjectId;

    /** 转入时 NBV 快照（BR-40 新原值基数） */
    @TableField("ORIG_NBV")
    private BigDecimal origNbv;

    /** 重建新卡锚 */
    @TableField("NEW_CARD_ID")
    private String newCardId;

    /** 新原值 = 转入时NBV + 项目已结算（BR-40） */
    @TableField("NEW_COST")
    private BigDecimal newCost;

    /** 剩余年限（技术评估值） */
    @TableField("NEW_LIFE_YEARS")
    private Integer newLifeYears;

    /** 转在建凭证锚（REWORK_TRANSFER） */
    @TableField("TRANSFER_VOUCHER_ID")
    private String transferVoucherId;

    /** 完工转资凭证锚（REWORK_REBUILD） */
    @TableField("REBUILD_VOUCHER_ID")
    private String rebuildVoucherId;

    /** 费用化凭证锚（REWORK_EXPENSE） */
    @TableField("EXPENSE_VOUCHER_ID")
    private String expenseVoucherId;

    /** 验收日（新卡生效日，次月起提锚） */
    @TableField("ACCEPT_DATE")
    private LocalDate acceptDate;

    /** DRAFT/PENDING_JUDGE/JUDGED/IN_REWORK/COMPLETED/REBUILT/EXPENSED/CLOSED */
    @TableField("REWORK_STATUS")
    private String reworkStatus;

    /** 判定复核/防拆分审批实例 */
    @TableField("APPR_ID")
    private String apprId;

    @TableField("REMARK")
    private String remark;
}
