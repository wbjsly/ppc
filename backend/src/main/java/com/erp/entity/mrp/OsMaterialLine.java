package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 外协发料单行（change add-outsourcing-processing，spec outsourcing-material-supply，迁移 126）：
 * 发料明细（物料/批次/仓库/请领量）+ 三累计列——实发（出库过账回写）、定额冲减（收货过账递增）、
 * 余料退回（退库过账回写）。行粒度对齐库存唯一键 (仓库, 物料, 批次)。
 */
@Getter
@Setter
@TableName("erp_mrp_os_material_line")
public class OsMaterialLine extends BaseEntity {

    private String osmId;
    private Integer lineNo;
    private String itemCode;
    private String itemName;
    /** 发料批次（库存唯一键组成，无批空串） */
    private String batchNo;
    /** 发出仓库 */
    private String warehouseCode;
    private String unit;
    /** 请领数量 */
    private BigDecimal reqQty;
    /** 实发数量（出库过账回写） */
    private BigDecimal issuedQty;
    /** 定额冲减累计（收货过账递增，BOM 快照用量×收货数；task 5.2） */
    private BigDecimal consumedQty;
    /** 余料退回累计（退库过账回写；task 5.2） */
    private BigDecimal returnedQty;
}
