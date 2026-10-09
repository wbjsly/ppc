package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 波次改批调整记录（4.8.1，spec wave-management C-4.4-08 强制审批，design D4）：
 * 审批 bizId 载体（bizType=WaveAdjust）；前值/后值/原因全程留痕。
 * PENDING → APPROVED（后值生效+回写发货行）/ REJECTED（保前值）。
 */
@Getter
@Setter
@TableName("erp_inv_wave_adjust")
public class InvWaveAdjust extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    /** 波次作废时关闭未决调整（留痕，回调按状态跳过业务动作） */
    public static final String ST_CANCELLED = "CANCELLED";

    private String waveId;

    /** 被调整的分配行（erp_inv_wave_line.ID） */
    private String lineId;

    /** BATCH 批次 / BIN 仓位 */
    private String field;

    private String oldValue;

    private String newValue;

    /** 调整原因（必填） */
    private String reason;

    /** 审批实例 ID（ApprovalInstance） */
    private String apprId;

    private String status;

    /** 发起人（签署防同人闭环 applyBy） */
    private String adjustBy;
}
