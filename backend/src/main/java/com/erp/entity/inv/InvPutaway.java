package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 仓位分配台账（4.4.5，spec bin-assignment，design D4）。
 * 状态机 RECOMMENDED → CONFIRMED；SUSPENDED = 推荐时无可用仓位（挂起标记，候选集恢复后可重推）。
 * 改派禁止原地改 BIN_CODE：生成新记录并以 SUPERSEDED_BY 反向关联旧记录（全留痕）。
 * CONFIRMED 是 GR 过账强前置（receipt-posting ①'）的取位依据；分配/上架本身不改变库存数量。
 * SOURCE_TYPE：GR（收货行过账前分配）/ STOCK（'' 未分配位补上架）。
 */
@Getter
@Setter
@TableName("erp_inv_putaway")
public class InvPutaway extends BaseEntity {

    public static final String ST_RECOMMENDED = "RECOMMENDED";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_SUSPENDED = "SUSPENDED";

    public static final String SRC_GR = "GR";
    public static final String SRC_STOCK = "STOCK";

    private String sourceType;
    /** 来源单号（GR 单号；STOCK=库存行 ID） */
    private String sourceDocNo;
    /** 来源行号（0=无行粒度） */
    private Integer sourceLineNo;
    private String warehouseCode;
    private String itemCode;
    private String batchNo;
    /** 分配/上架数量（记录语义量；分配不改库存） */
    private BigDecimal qty;
    private String binCode;
    private String status;
    /** 被哪条新记录替代（改派留痕；NULL=当前生效记录） */
    private String supersededBy;
    private String remark;
}
