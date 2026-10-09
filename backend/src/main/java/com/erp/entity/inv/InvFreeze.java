package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冻结台账（spec freeze-management，FR-4.4-5 / C-4.4-05；迁移 103）。
 * 一表状态流转：PENDING（待审批）→ ACTIVE（生效）→ RELEASED（已解冻），REJECTED 审批驳回。
 * 单笔冻结类型唯一；同一库存行 QUALITY 与 FINANCE 两类可叠加（BR-4.4-14）。
 * SOURCE=MANUAL 走本表状态机；SOURCE=NCR 由 NCR 流程写入并推进（页面只读，解冻走 NCR）。
 */
@Getter
@Setter
@TableName("erp_inv_freeze")
public class InvFreeze extends BaseEntity {

    public static final String T_QUALITY = "QUALITY";
    public static final String T_FINANCE = "FINANCE";

    public static final String ST_PENDING = "PENDING";
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_RELEASED = "RELEASED";
    public static final String ST_REJECTED = "REJECTED";

    public static final String SRC_MANUAL = "MANUAL";
    public static final String SRC_NCR = "NCR";
    /** 拣货复核外观异常自动发起（picking-review design D4；SOURCE VARCHAR(8) 故用短值） */
    public static final String SRC_REVIEW = "REVIEW";

    /** 影响范围：全部库存 / 指定批次 / 指定仓位（FR-4.4-5-1） */
    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_BATCH = "BATCH";
    public static final String SCOPE_BIN = "BIN";

    private String freezeNo;
    private String freezeType;
    private String warehouseCode;
    private String itemCode;
    private String itemName;
    private String batchNo;
    private BigDecimal qty;
    private String reason;
    private String scope;
    private String status;
    private String source;
    private String ncrId;
    /** 冻结审批实例（BIZ_TYPE=FREEZE） */
    private String apprId;
    /** 解冻审批实例（独立审批 FR-4.4-5-7） */
    private String unfreezeApprId;
    /** 发起人（解冻仅原发起人 FR-4.4-5-6） */
    private String applyBy;
    private String releaseResult;
    private String releaseBasis;
    private String releasedBy;
    private LocalDateTime releasedAt;
    private String remark;
    /** 行级平移明细 JSON [{binCode, qty}]（change add-bin-assignment design D7：解冻原路回补） */
    private String detailJson;
    /**
     * 影响面快照 JSON（迁移 112，FR-4.4-5-4/5.5，4.9.4 展开）：
     * {sos:[{soNo,lineId,qty,precision}], pos:[{poNo,lineNo,qty,precision}],
     *  mos:[], moDataSource:false}
     * SO=预留反查精确到批次；PO=未清 PO 行物料级；工单数据源未落地（proposal D1）
     */
    private String impactJson;
}
