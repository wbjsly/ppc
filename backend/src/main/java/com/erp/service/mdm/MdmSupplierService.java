package com.erp.service.mdm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.mdm.MdmSupplierCert;
import com.erp.entity.mdm.MdmSupplierVersion;

import java.util.List;
import java.util.Map;

/**
 * 供应商准入审核业务能力契约（供方管理 1.4.1，行 550 + S-4.1-10 + BR-4.2-18 五态）。
 */
public interface MdmSupplierService {

    /** 分页（keyword/status 筛选；查询前置证照到期懒巡检，返回 nearExpiry 标记） */
    Page<MdmSupplier> page(long current, long size, String keyword, String status);

    MdmSupplier getById(String id);

    /**
     * 建档：SUP-NNNN 自动编码（创建后不可改）；黑名单 HIT 422；
     * 税号+名称≤3 查重（C-4.1-07，forceCreate+dupNote 放行）；状态 PENDING + CREATE 快照 + CREATED 事件。
     */
    MdmSupplier create(MdmSupplier supplier, boolean forceCreate);

    /** 变更：编码锁 422、原因必填、HIT 双入口阻断、UPDATE 快照 + UPDATED 事件 */
    MdmSupplier update(MdmSupplier supplier);

    /**
     * 单步准入审核（仅 PENDING）：APPROVED → QUALIFIED（校验证照无过期）；
     * REJECTED → 留痕驳回原因保持 PENDING。REVIEW/REJECTED 快照 + 事件。
     */
    MdmSupplier review(String id, String result, String reason);

    /**
     * 状态迁移（design D2 矩阵单点守卫）：FROZEN/DISABLED 与其恢复、
     * 启用恢复态判定 resolveAfterRelease（证照过期 → CERT_EXPIRED）。原因必填。
     */
    MdmSupplier changeStatus(String id, String toStatus, String reason);

    /** 证照核验解除（前置 CERT_EXPIRED + 存在未来有效证照）→ QUALIFIED + CERT_RENEWED 事件 */
    MdmSupplier verifyCerts(String id, String reason);

    /** 证照增改（入供应商快照；到期判定由懒巡检负责） */
    MdmSupplierCert saveCert(MdmSupplierCert cert);

    void deleteCert(String certId);

    List<MdmSupplierCert> certs(String supplierId);

    /** 影响分析：最早到期证照 + 受限态 + 过期证照清单 + PO 桩（采购域未接入） */
    Map<String, Object> impact(String id);

    /** 合格供应商下拉（options 前置懒巡检，仅 QUALIFIED） */
    List<Map<String, String>> options();

    List<MdmSupplierVersion> versions(String entityId);

    Map<String, Object> diff(String entityId, int from, int to);

    /** 证照到期懒巡检（幂等：置 CERT_EXPIRED + 快照 + 事件），供 page/impact/options 前置调用 */
    int sweepExpiredCerts();
}
