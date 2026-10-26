package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 盘点明细（add-asset-count，design D1/D3）：
 * 物理快照列（清单下发冻结，BR-29 比对基准）+ 实盘列 + 差异列；
 * UNIQUE(PLAN_ID, CARD_NO)；盘盈行 CARD_ID 空、CARD_NO= SURPLUS- 合成号。
 */
@Getter
@Setter
@TableName("erp_eam_count_line")
public class EamCountLine extends BaseEntity {

    @TableField("PLAN_ID")
    private String planId;

    @TableField("CNT_NO")
    private String cntNo;

    // ---- 快照列（清单下发时冻结） ----

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    @TableField("SNAP_NAME")
    private String snapName;

    @TableField("SNAP_DEPT")
    private String snapDept;

    @TableField("SNAP_CC")
    private String snapCc;

    @TableField("SNAP_LOCATION")
    private String snapLocation;

    @TableField("SNAP_CATEGORY")
    private String snapCategory;

    @TableField("SNAP_COST")
    private BigDecimal snapCost;

    @TableField("SNAP_ACCUM")
    private BigDecimal snapAccum;

    /** 盘亏核销基准（BR-28） */
    @TableField("SNAP_NBV")
    private BigDecimal snapNbv;

    @TableField("SNAP_STATUS")
    private String snapStatus;

    // ---- 实盘列 ----

    /** IN_USE/IDLE/DAMAGED/PENDING_SCRAP（四实态）/MISSING 盘亏/SURPLUS 盘盈/UNKNOWN 无标签待确认 */
    @TableField("ACTUAL_STATUS")
    private String actualStatus;

    @TableField("ACTUAL_NOTE")
    private String actualNote;

    @TableField("COUNTED_BY")
    private String countedBy;

    @TableField("COUNTED_AT")
    private LocalDateTime countedAt;

    /** SCAN/MANUAL/IMPORT */
    @TableField("COUNT_METHOD")
    private String countMethod;

    /** 实盘在用刷新 LAST_USED_AT（S-4.13-13） */
    @TableField("REFRESH_LAST_USED_FLAG")
    private String refreshLastUsedFlag;

    // ---- 差异列 ----

    @TableField("DIFF_FLAG")
    private String diffFlag;

    /** OVER/SHORT/TAG_ISSUE/STATUS_CONFLICT/PENDING_CONFIRM */
    @TableField("DIFF_TYPE")
    private String diffType;

    @TableField("REASON")
    private String reason;

    @TableField("EVIDENCE")
    private String evidence;

    @TableField("REMARK")
    private String remark;
}
