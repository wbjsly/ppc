package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 外协甲供冲减台账（change add-outsourcing-processing，task 5.2，spec
 * outsourcing-material-supply「收货定额冲减与余料退回」，迁移 126）：
 * 收货定额冲减（CONSUME，幂等锚=GR 单号）与红冲恢复（REVERSE，幂等锚=退货单号）逐行留痕。
 * UNIQUE(ANCHOR_NO, OSM_LINE_ID, CONSUME_TYPE) 为幂等防线；QTY 恒为正数（REVERSE=恢复量）。
 */
@Getter
@Setter
@TableName("erp_mrp_os_consume")
public class OsConsume extends BaseEntity {

    public static final String TYPE_CONSUME = "CONSUME";
    public static final String TYPE_REVERSE = "REVERSE";

    /** 外协派工单 */
    private String dispatchId;
    /** 发料单 */
    private String osmId;
    /** 发料行 */
    private String osmLineId;
    /** CONSUME 收货定额冲减 / REVERSE 红冲恢复 */
    private String consumeType;
    /** 幂等锚：CONSUME=GR 单号 / REVERSE=退货单号 */
    private String anchorNo;
    private String itemCode;
    private String itemName;
    /** 发料批次（库存唯一键组成） */
    private String batchNo;
    /** 发料仓库 */
    private String warehouseCode;
    /** 本次冲减/恢复数量（正数） */
    private BigDecimal qty;
}
