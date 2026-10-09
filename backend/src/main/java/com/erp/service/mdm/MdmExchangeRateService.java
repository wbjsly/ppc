package com.erp.service.mdm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.mdm.MdmExchangeRate;
import com.erp.entity.mdm.MdmExchangeRateVersion;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 汇率维护业务能力契约（汇率管理 1.5.1，FR-4.1-3-1 + BR-4.1-16/17/18）。
 */
public interface MdmExchangeRateService {

    /**
     * 分页：baseCcy/quoteCcy/rateType/lifecycle（计算态）筛选；
     * 每行附 lifecycle（NOT_EFFECTIVE/EFFECTIVE/EXPIRED）计算态，不落库。
     */
    Page<Map<String, Object>> page(long current, long size, String baseCcy, String quoteCcy,
                                   String rateType, String lifecycle);

    MdmExchangeRate getById(String id);

    /**
     * 创建：ISO 4217 格式 + BASE≠QUOTE、必填四件（BR-4.1-16）、RATE>0 精度 6 位、
     * 失效日≥生效日、区间冲突/断档（BR-4.1-17，币对×类型序列）；CREATE 快照 + CREATED 事件。
     */
    MdmExchangeRate create(MdmExchangeRate rate);

    /**
     * 变更：序列键（BASE/QUOTE/TYPE）创建后锁定 422、历史（失效日<今天）422（BR-4.1-18）、
     * 原因必填、重跑区间校验、UPDATE 快照 + UPDATED 事件。
     */
    MdmExchangeRate update(MdmExchangeRate rate);

    /** 删除：历史 422；生效中/未生效软删（重跑序列完整性由后续录入校验兜底） */
    void delete(String id);

    /**
     * 试算（FR-4.6-1-5 预铺）：缺省类型 MIDDLE→BUY→SELL 回退并标明实际类型；
     * 无命中 → 明示「该日期无有效汇率」+ 非空 reasons。
     */
    Map<String, Object> trial(String baseCcy, String quoteCcy, LocalDate date, String rateType);

    List<MdmExchangeRateVersion> versions(String entityId);

    Map<String, Object> diff(String entityId, int from, int to);

    /**
     * 区间历史链（1.5.3）：币对×类型全量升序（含已失效）+ 计算态；三参必填 422，空序列返回空列表。
     */
    List<Map<String, Object>> sequence(String baseCcy, String quoteCcy, String rateType);

    /**
     * 全局版本时间线（1.5.3）：跨记录快照倒序分页（记录标识从 SNAPSHOT_JSON 解析，软删不缺失），
     * rateType/opType/keyword 筛选，每行附 outbox 事件（幂等键重建匹配，无命中 null）。
     */
    Map<String, Object> history(long current, long size, String rateType, String opType, String keyword);
}
