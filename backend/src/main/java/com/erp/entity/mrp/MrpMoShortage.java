package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 工单缺料清单行（change add-work-order-management，FR-4.5-3-6 / BR-4.5-04；迁移 120）。
 * KittingService（唯一齐套算法）的输出载体：创建与释放时调用，5.5 齐套检查菜单复用。
 * REQ_QTY = 工单 QTY × 单位用量 × (1+损耗率)；RESOLVED_FLAG=1 表示已齐套（自动清除缺料标记）。
 */
@Getter
@Setter
@TableName("erp_mrp_mo_shortage")
public class MrpMoShortage extends BaseEntity {

    private String moId;
    /** 对应 BOM 快照行号 */
    private Integer bomLine;
    private String itemCode;
    private String itemName;
    private BigDecimal reqQty;
    private BigDecimal availQty;
    private BigDecimal shortQty;
    /** 已齐套 1 */
    private String resolvedFlag;
}
