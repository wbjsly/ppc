package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收货行（change add-goods-receipt design D1/D2）。
 * 不继承 BaseEntity——本表无 DEL_FLAG 列；verNo 带 @Version 乐观锁（过账并发，spec receipt-posting）。
 */
@Getter
@Setter
@TableName("erp_proc_gr_line")
public class GoodsReceiptLine {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String grId;
    private Integer lineNo;
    /** FREE 行为空 */
    private String poLineId;

    private String itemCode;
    private String itemName;
    private String unit;

    /** 登记时快照：PO 下单量 / 未清量 */
    private BigDecimal orderedQty;
    private BigDecimal openQty;
    private BigDecimal receivedQty;

    /** OK_OVER / OK_SHORT / OVER / SHORT / FREE（C-4.2-04 分流） */
    private String toleranceResult;
    /** 容差内核销量 */
    private BigDecimal withinToleranceQty;
    /** 超容差冻结量（待处理区，BR-4.2-21） */
    private BigDecimal frozenQty;

    /** PENDING / POSTED / REJECTED */
    private String status;

    /**
     * 检验状态（spec goods-receipt）：NONE / PENDING / RELEASED / CONCESSION / FROZEN / SKIPPED。
     * 仅由检验批动作驱动，禁止手工改（039 迁移新增列，默认 NONE）。
     */
    @TableField("QC_STATUS")
    private String qcStatus;

    /** 过账单价与金额快照（PO 行单价；FREE 可空） */
    private BigDecimal unitPrice;
    private BigDecimal amount;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;

    @Version
    private Integer verNo;
}
