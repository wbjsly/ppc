package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 调拨单头（4.12.1 仓间调拨 / 4.12.2 在途跟踪 / 4.5.3 调拨出库 / 4.4.4 调拨入库共享实体，
 * spec transfer-order，design D3 状态机）。
 * DRAFT → OUT_POSTED → IN_POSTED → CLOSED（+CANCELLED）；SUSPENDED_FLAG 为在途超期挂起标记
 * （保留 OUT_POSTED 语义，C-4.4-09）；CROSS_LE=1 时触发跨法人凭证/发票链
 * （spec internal-transfer-accounting，design D4）。
 */
@Getter
@Setter
@TableName("erp_inv_transfer_order")
public class InvTransferOrder extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_OUT_POSTED = "OUT_POSTED";
    public static final String ST_IN_POSTED = "IN_POSTED";
    public static final String ST_CLOSED = "CLOSED";
    public static final String ST_CANCELLED = "CANCELLED";

    /** TR+yyyyMMdd+流水 */
    private String transferNo;
    private String outWhCode;
    private String inWhCode;
    private String outLeCode;
    private String inLeCode;
    /** 1=跨法人（凭证+发票链）/ 0=同法人移库（零凭证） */
    private String crossLe;
    private String status;
    /** 在途超期挂起 1/0（调度写入，幂等） */
    private String suspendedFlag;
    private LocalDateTime outPostAt;
    private LocalDateTime inPostAt;
    private String closeBy;
    private LocalDateTime closeAt;
    private String cancelReason;
    private BigDecimal totalQty;
    private BigDecimal totalAmount;
    /** 出库过账分配结果 JSON（入库段按批入账依据） */
    private String allocJson;
    private String remark;
}
