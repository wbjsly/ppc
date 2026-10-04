package com.erp.service.mdm;

import com.erp.entity.mdm.MdmPriceAgreement;
import com.erp.entity.mdm.MdmPriceAgreementVersion;

import java.util.List;
import java.util.Map;

/**
 * 跨域共享业务能力契约（客户管理 1.3.3：事件流台账 + 价格协议 + 试算）。
 */
public interface MdmCrossDomainService {

    // ---------- 事件流（7.2 事件契约台账） ----------

    /** 事件流分页：eventType/status/keyword（业务编码或幂等键）筛选 */
    Map<String, Object> outboxPage(long current, long size, String eventType,
                                   String status, String keyword);

    /** 事件详情（含 payload 与桩口径字段） */
    Map<String, Object> outboxDetail(String id);

    // ---------- 价格协议（FR-4.1-6-2 缺口 + FR-4.3-1-4 载体） ----------

    /** 协议列表（前置懒过期 sweep）：keyword/类型/状态筛选 */
    Map<String, Object> agreementPage(long current, long size, String keyword,
                                      String agreementType, String status);

    MdmPriceAgreement getAgreement(String id);

    /**
     * 创建协议：PA-NNNN 自动编码；挂靠集团/法人二选一 422；
     * 建已过期 422；行校验（SKU 启用、LADDER 闭区间重叠、非阶梯单价）。
     * 行经 payload.lines 传入（transient，见实现）。
     */
    MdmPriceAgreement createAgreement(MdmPriceAgreement agreement, List<Map<String, Object>> lines);

    /** 变更协议头+行：编码锁定；仅 0/1 可编辑（2/3 422）；原因必填入快照 */
    MdmPriceAgreement updateAgreement(MdmPriceAgreement agreement, List<Map<String, Object>> lines);

    /** 停用（0/1→3）与恢复（3→按日期推算 0/1），原因必填 */
    MdmPriceAgreement stopAgreement(String id, String reason);

    List<MdmPriceAgreementVersion> agreementVersions(String entityId);

    Map<String, Object> agreementDiff(String entityId, int from, int to);

    /**
     * 试算（FR-4.3-4-3 优先级子集）：挂靠双级、EXCLUSIVE>LADDER>TIME、
     * 法人级优先、LADDER 闭区间、无命中明示 reasons。
     * 入参 groupId/viewId 至少一个。
     */
    Map<String, Object> trial(String groupId, String viewId, String itemCode,
                              java.math.BigDecimal qty, java.time.LocalDate date);

    /** 懒过期 sweep（design D5）：STATUS 0/1 且 EXPIRE_DATE<今天 → 置 2 + 快照；幂等 */
    int sweepExpiredAgreements();
}
