package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 可疑检验批次清单（BR-4.12-06 / C-4.12-12）：
 * 量具校准不合格 → 反向追溯自上次合格校准日以来参与的检验批；
 * STATUS=PENDING_EVAL 期间阻断放行，质量工程师 5 工作日内出评估结论。
 */
@Getter
@Setter
@TableName("erp_qms_suspect_lot")
public class SuspectLot extends BaseEntity {
    /** 器具 ID */
    @TableField("GAUGE_ID")
    private String gaugeId;

    /** 器具编码 */
    @TableField("GAUGE_CODE")
    private String gaugeCode;

    /** 校准记录 ID */
    @TableField("CAL_RECORD_ID")
    private String calRecordId;

    /** 检验批 ID */
    @TableField("LOT_ID")
    private String lotId;

    /** 检验批号 */
    @TableField("LOT_NO")
    private String lotNo;

    /** 物料编码 */
    @TableField("ITEM_CODE")
    private String itemCode;

    /** 可疑原因 */
    @TableField("SUSPECT_REASON")
    private String suspectReason;

    /** PENDING_EVAL / CLEARED / BLOCKED */
    @TableField("STATUS")
    private String status;

    /** 评估人 */
    @TableField("EVAL_BY")
    private String evalBy;

    /** 评估时间 */
    @TableField("EVAL_TIME")
    private LocalDateTime evalTime;

    /** 评估结论 */
    @TableField("EVAL_CONCLUSION")
    private String evalConclusion;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
