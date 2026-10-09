package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/** 询价单供应商清单（RFQ×SUPPLIER 唯一）。 */
@Data
@TableName("erp_proc_rfq_supplier")
public class RfqSupplier implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String rfqId;

    private String supplierId;

    private String createBy;

    private java.time.LocalDateTime createDate;
}
