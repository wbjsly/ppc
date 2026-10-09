package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 批次台账（4.2.1，spec batch-master，design D1）：
 * 批次号同物料唯一（UK）且创建后不可改；系统生成 `B+yyMMdd+-+4位日流水` 或手工录入；
 * 批次管理物料有效期至必填（BR-4.1-08）；状态 1 正常 / 0 关闭（退出后续可选范围，不影响既有引用）。
 */
@Getter
@Setter
@TableName("erp_inv_batch")
public class InvBatch extends BaseEntity {

    /** 同物料唯一（UK_INV_BATCH），创建后不可改 */
    private String batchNo;

    private String itemCode;

    private String itemName;

    /** 生产日期 */
    private LocalDate productionDate;

    /** 有效期至（批次管理物料必填，> 生产日期） */
    private LocalDate expiryDate;

    /** 供应商批次号（追溯索引 BR-4.4-48） */
    private String supplierBatchNo;

    /** 来源单据号 */
    private String sourceDocNo;

    /** 1 正常 / 0 关闭 */
    private String status;

    private String remark;
}
