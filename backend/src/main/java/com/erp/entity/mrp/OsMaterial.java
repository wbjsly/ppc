package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 外协发料单（change add-outsourcing-processing，spec outsourcing-material-supply
 * 「外协发料单创建与出库」，迁移 126）：供料方式=甲供的外协派工单从仓库调拨原材料至供应商保管。
 * 状态机：DRAFT（可改/可删）→ POSTED（已出库，只读；定额冲减/余料退回在行上累计）→ RETURNED（余料已退）。
 * 所有权留厂内，仅保管责任转移供应商；出库过账 OS_MAT_OUT 扣 AVAILABLE_QTY。
 */
@Getter
@Setter
@TableName("erp_mrp_os_material")
public class OsMaterial extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_POSTED = "POSTED";
    public static final String ST_RETURNED = "RETURNED";

    /** 供料方式：甲供（本单据唯一适用，自购 422） */
    public static final String SUPPLY_COMPANY = "COMPANY_SUPPLY";
    /** 供料方式：供应商自购（不可甲供发料） */
    public static final String SUPPLY_SUPPLIER = "SUPPLIER_BUY";

    /** 发料单号 OSM+yyyyMMdd+流水 */
    private String osmNo;
    /** 外协派工单（OS_SUPPLY_TYPE=COMPANY_SUPPLY 前置校验） */
    private String dispatchId;
    private String dispatchNo;
    /** 工单号（冗余查询） */
    private String moNo;
    /** 工序序号 */
    private Integer opSeq;
    /** 收料供应商（随外协 WC 关联） */
    private String supplierCode;
    private String supplierName;
    /** DRAFT / POSTED / RETURNED */
    private String status;
    private String postBy;
    private LocalDateTime postDate;
    /** 余料退回完成时间 */
    private LocalDateTime returnDate;
    private String remark;

    /** 待退回余量合计 = Σ 行（实发 − 已冲减 − 已退回），瞬态（查询回填，非表列） */
    @TableField(exist = false)
    private BigDecimal remainQty;
}
