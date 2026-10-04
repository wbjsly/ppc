package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 招标行（物料快照，同 RfqLine 惯例：不继承 BaseEntity，表仅含创建审计列）。 */
@Data
@TableName("erp_proc_tender_line")
public class TenderLine implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenderId;

    private Integer lineNo;

    private String itemCode;

    private String itemName;

    private BigDecimal qty;

    private String unit;

    private LocalDate reqDate;

    private String createBy;

    private java.time.LocalDateTime createDate;
}
