package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 投标供应商与资格审查（FR-4.2-10-1 资质门槛，BR-4.2-45 合格家数判定）。
 * TENDER_ID × SUPPLIER_ID 唯一。
 */
@Data
@TableName("erp_proc_tender_supplier")
public class TenderSupplier implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenderId;

    private String supplierId;

    private String supplierName;

    /** APPLY 报名 / INVITE 邀请 */
    private String joinSource;

    /** PENDING 待审 / PASS 合格 / FAIL 不合格 */
    private String qualifyStatus;

    private String qualifyNote;

    private String qualifyBy;

    private LocalDateTime qualifyDate;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}
