package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 供应商合并日志（BR-4.1-03 永久保留 + C-4.1-11 审计，表 erp_mdm_supplier_merge_log）。
 * 只追加 + 回退回填，无 DEL_FLAG 不软删；ORIGINAL_SUPPLIER_CODE 为 BR-4.1-28 反查锚点。
 */
@Data
@TableName("erp_mdm_supplier_merge_log")
public class MdmSupplierMergeLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** MG-NNNN 人读单号 */
    private String logNo;

    private String sourceId;

    private String sourceCode;

    private String targetId;

    private String targetCode;

    /** C-4.1-11 BeforeValue：合并前状态 */
    private String preStatus;

    /** 影响面摘要（证照 N 份 + 四类桩口径） */
    private String impactSummary;

    /** = sourceCode，BR-4.1-28 合并前归属反查锚点 */
    private String originalSupplierCode;

    private LocalDateTime mergeAt;

    private String operator;

    /** 0 未回退 / 1 已回退 */
    private String reverted;

    private LocalDateTime revertAt;

    private String revertReason;

    private String revertOperator;

    private String createBy;

    private LocalDateTime createDate;
}
