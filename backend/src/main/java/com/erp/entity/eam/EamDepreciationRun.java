package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 折旧计提单（add-asset-depreciation，design D1）：
 * 会计月 PERIOD_TAG 唯一幂等锚（同月已提 422），一张单对应一张自动过账折旧凭证（BR-4.6-32）。
 */
@Getter
@Setter
@TableName("erp_eam_depreciation_run")
public class EamDepreciationRun extends BaseEntity {

    /** JZ + yyyyMM */
    @TableField("RUN_NO")
    private String runNo;

    /** 会计月 yyyyMM（唯一幂等锚） */
    @TableField("PERIOD_TAG")
    private String periodTag;

    /** POSTED / REVERSED */
    @TableField("STATUS")
    private String status;

    /** 折旧凭证（voucherType=DEP） */
    @TableField("VOUCHER_ID")
    private String voucherId;

    /** 本期折旧合计 */
    @TableField("TOTAL_DEPR")
    private BigDecimal totalDepr;

    /** BR-4.13-13 勾稽差异（明细汇总 vs 总账折旧科目本期发生额） */
    @TableField("RECON_DIFF")
    private BigDecimal reconDiff;

    /** 残值预警张数（C-4.6-04） */
    @TableField("WARNING_COUNT")
    private Integer warningCount;

    /** 1=已冲回（整单全冲） */
    @TableField("REVERSED_FLAG")
    private String reversedFlag;

    /** 冲回关联的变动单 ID（erp_eam_asset_change） */
    @TableField("REVERSE_CHANGE_ID")
    private String reverseChangeId;

    @TableField("REMARK")
    private String remark;
}
