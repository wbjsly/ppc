package com.erp.service.qms;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 质量放行闸口（tasks 7.5，D8 对外接口桩）：
 * 4.5 出库等下游域在出库/领用前调用，按物料+批次给出放行决策。
 *
 * 返回：decision ∈ {ALLOWED, CONCESSION, BLOCKED} + message；
 * CONCESSION 时附让步单与剩余额度（须先核销），BLOCKED 附锁定量与原因。
 */
public interface QualityGateService {

    /**
     * 批次放行校验。
     *
     * @param itemCode 物料编码
     * @param batchNo  批次号（无批次传空串）
     * @param qty      本次出库/领用数量
     * @param scope    使用范围/用途描述（与让步限制条件比对）
     */
    Map<String, Object> checkIssue(String itemCode, String batchNo, BigDecimal qty, String scope);

    /**
     * 发货确认 OQC 前置校验（change rework-inspection-tasks，spec 发货确认 OQC 前置校验）。
     * 按出货行「物料+批次」查 OQC 批：有批未放行 → 422 阻断列出；无批按参数 OQC_ENFORCE
     * （默认 ON）ON→422 提示先建批/免检，OFF→返回降级 warnings；SKIPPED 视同放行。
     * 通过时不抛错（返回含 warnings 的决策 Map）。
     */
    Map<String, Object> assertOqcForShipment(java.util.List<java.util.Map<String, Object>> lines);
}
