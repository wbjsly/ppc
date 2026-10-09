package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 库存日结余额快照（spec stock-snapshot，FR-4.4-2-6 / BR-4.4-17；迁移 103）。
 * 插入即终态：应用侧不提供 update 路径，已结算日的行永不修改（只读语义，偏差 D3）；
 * 同日重跑 = 不存在才插入（单事务全有或全无）。
 */
@Getter
@Setter
@TableName("erp_inv_daily_balance")
public class InvDailyBalance extends BaseEntity {

    private LocalDate balDate;
    private String warehouseCode;
    private String itemCode;
    private String itemName;
    private String batchNo;
    /** 仓位编号（日结粒度 = 日期+仓库+物料+批次+仓位，迁移 105） */
    private String binCode;
    private BigDecimal qty;
    private BigDecimal availableQty;
    private BigDecimal qcQty;
    private BigDecimal finQty;
}
