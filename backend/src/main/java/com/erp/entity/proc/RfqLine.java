package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 询价单行（PR 行快照：物料/数量/交付要求，PR 后续变更不影响已发 RFQ）。 */
@Data
@TableName("erp_proc_rfq_line")
public class RfqLine implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String rfqId;

    private Integer lineNo;

    private String itemCode;

    private BigDecimal qty;

    private LocalDate reqDate;

    private String createBy;

    private java.time.LocalDateTime createDate;
}
