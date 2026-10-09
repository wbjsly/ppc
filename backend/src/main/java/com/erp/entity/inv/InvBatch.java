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

    /** 批次状态 */
    public static final String SRC_AUTO = "AUTO";
    public static final String SRC_MANUAL = "MANUAL";

    /** 同物料唯一（UK_INV_BATCH），创建后不可改 */
    private String batchNo;

    private String itemCode;

    private String itemName;

    /** 生产日期 */
    private LocalDate productionDate;

    /** 有效期至（批次管理物料必填，> 生产日期） */
    private LocalDate expiryDate;

    /** 效期锁定 1/0（spec outbound-strategy / batch-master MODIFIED）：
     *  剩余天数 < 有效期总天数 × EXPIRY_LOCK_RATIO 时由扫描/实时计算置位，用户不可编辑（C-4.4-03） */
    private String expiryLockFlag;

    /** 锁定来源（迁移 113，expiry-management 需求③）：AUTO 系统扫描 / MANUAL 人工锁定。
     *  MANUAL 扫描只评估不清除——解除人工锁唯一入口=评估放行 */
    private String lockSource;

    /** 让步放行截止日（NULL=无豁免）：未过期时扫描强制不锁、引擎④放行；到期回归管控 */
    private java.time.LocalDate evalExemptUntil;

    /** 放行评估单号（来源追溯） */
    private String evalExemptId;

    /** 供应商批次号（追溯索引 BR-4.4-48） */
    private String supplierBatchNo;

    /** 来源单据号 */
    private String sourceDocNo;

    /** 1 正常 / 0 关闭 */
    private String status;

    private String remark;
}
