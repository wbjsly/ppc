package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 交付计划行（分批交付，FR-4.2-1-1 SOP）：Σ(QTY) ≤ 行需求量。 */
@Data
@TableName("erp_proc_pr_delivery_line")
public class ProcPrDeliveryLine implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String prLineId;

    private LocalDate deliveryDate;

    private BigDecimal qty;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}
