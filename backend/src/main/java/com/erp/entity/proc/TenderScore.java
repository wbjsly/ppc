package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 招标评分卡（FR-4.2-10-2，4 维宽表 design D4；BR-4.2-47 提交后不可直接修改）。
 * TENDER_ID × SUPPLIER_ID × JUDGE_USER_ID 唯一。
 */
@Data
@TableName("erp_proc_tender_score")
public class TenderScore implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenderId;

    private String supplierId;

    private String judgeUserId;

    // ---- 四维原始分（0-100），权重取招标头快照 ----
    private Integer scorePrice;
    private Integer scoreDelivery;
    private Integer scoreQuality;
    private Integer scoreCooperation;

    /** 加权得分 = Σ(维度分 × 权重) / 100 */
    private BigDecimal weightedScore;

    /** DRAFT 草稿 / SUBMITTED 已提交（提交后锁定） */
    private String status;

    private LocalDateTime submitTime;

    /** 合规审批后方可修改（BR-4.2-47）：0 未获准 / 1 已获准 */
    private String amendApproved;
    private String amendBy;
    private LocalDateTime amendDate;
    private String amendNote;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}
