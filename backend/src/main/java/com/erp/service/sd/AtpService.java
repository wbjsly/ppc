package com.erp.service.sd;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.sd.AtpTrial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ATP 承诺与分批交付服务（tasks 8.2~8.5，spec sales-atp-reservation）。
 *
 * 公式（BR-4.3-19，SKU + 仓库维度）：
 *   Available = OnHand + InProcess(恒0，生产域未接) + Incoming − Reserved − SafetyStock
 *  - OnHand 取 erp_inv_stock.AVAILABLE_QTY（已排除待检/让步锁定），寄售库存单列排除；
 *  - Incoming 取已批 APPROVED PO 未收量，预计到货晚于客户期望交期的剔除（BR-4.3-20）；
 *  - Reserved = 预留表 SUM(ACTIVE) 实时汇总（非存储字段）；
 *  - SafetyStock = max(物料 SAFETY_STOCK, 日均销量 × SAFETY_DAYS)，不参与承诺（BR-4.3-21）；
 *  - ATP 不足仅 L4 提示可承诺量与最早可承诺日期，不削量（BR-4.3-22 / C-4.3-02）。
 */
public interface AtpService {

    /**
     * 承诺试算（3.4.1）：四因子明细 + 延迟剔除标记 + 安全库存占用 + L4 提示 + 最早可承诺日期。
     *
     * @param expectDate 客户期望交期（空 = 不做延迟剔除分组，全部在途计入）
     */
    Map<String, Object> trial(String itemCode, String warehouseCode,
                              BigDecimal qty, LocalDate expectDate);

    /** 保存试算记录（spec 场景「可保存试算记录」）——从 trial 结果落台账 */
    AtpTrial saveTrial(Map<String, Object> trialResult, String remark);

    Page<AtpTrial> trialPage(long current, long size, String itemCode, String status);

    /**
     * 分批交付拆行（8.5 / FR-4.3-3-6 / BR-4.3-23）：
     * 守恒校验（Σ = 原行，否则 422 回滚）+ 逐行独立交期绑定；
     * SO 已确认时释放原行预留并按新行重新锁批（锁不足 → 422 整体回滚）。
     *
     * @param batches [{qty, planShipDate, warehouseCode?}]，至少两批
     * @return {lines, totalBefore, totalAfter, reserved}
     */
    Map<String, Object> splitLine(String lineId, List<Map<String, Object>> batches);

    /**
     * SO 行级 ATP 检查（建单/确认前的 C-4.3-02 L4 提示数据）：
     * 每行返回 {lineNo, itemCode, qty, atp, enough, earliestPromiseDate, warnings}。
     */
    List<Map<String, Object>> checkSoLines(String soId);
}
