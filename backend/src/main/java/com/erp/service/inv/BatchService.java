package com.erp.service.inv;

import com.erp.entity.inv.InvBatch;

import java.time.LocalDate;
import java.util.List;

/**
 * 批次台账（4.2.1，spec batch-master）能力契约：
 * 批次号系统生成（B+yyMMdd+-+4位日流水）或手工录入，同物料唯一、创建后不可改；
 * 批次管理物料有效期至必填（BR-4.1-08）；查询支持物料/批次号/供应商批次/效期区间。
 */
public interface BatchService {

    /** 查询（物料/批次号关键字/供应商批次/效期区间，效期升序） */
    List<InvBatch> query(String itemCode, String keyword, String supplierBatchNo,
                         LocalDate expiryFrom, LocalDate expiryTo, String status);

    /** 单个批次 */
    InvBatch get(String id);

    /** 新建（批次号留空=自动生成；同物料唯一 409；效期校验 422） */
    InvBatch create(InvBatch batch);

    /** 变更（批次号锁定 422；效期与物料属性可改） */
    InvBatch update(InvBatch batch);

    /** 关闭（退出后续可选范围，不影响既有引用） */
    void disable(String id);

    /** 重新启用 */
    void enable(String id);
}
