package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SO 批次预留（spec sales-atp-reservation / sales-order 7.7）。
 * BR-4.3-29：SO 确认同事务按行生成预留；BR-4.3-24：同批次先到先得（LOCK_AT 时间戳裁决）。
 * ATP 的 Reserved 因子 = SUM(ACTIVE.QTY)（实时汇总，非存储字段）。
 * 发货消耗 → CONSUMED；取消/关闭余量/变更减量 → RELEASED（信用冻结不释放）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_reservation")
public class Reservation extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_CONSUMED = "CONSUMED";
    public static final String ST_RELEASED = "RELEASED";

    private String soId;
    private String soNo;
    private String lineId;
    private Integer lineNo;
    private String itemCode;
    private String warehouseCode;
    /** 无批次物料用空串占位（8.6） */
    private String batchNo;
    private BigDecimal qty;
    private String status;
    /** 先到先得裁决基准（BR-4.3-24） */
    private LocalDateTime lockAt;
    private LocalDateTime releasedAt;
    private String releaseReason;
}
