package com.erp.service.sd;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.sd.DiscountChannel;
import com.erp.entity.sd.PriceAudit;
import com.erp.entity.sd.Promotion;
import com.erp.entity.sd.SpecialPrice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 三层折扣引擎与价格治理（spec sales-pricing-discount，design D4）：
 * 渠道折扣（BR-4.3-32 缺失置 0 + L4）→ 量价阶梯（行级不跨 SKU，复用 LADDER 协议）→
 * 时间促销（重叠取最大 BR-4.3-33）→ 叠加（默认最优单层，STACKED 逐层递减，求和异常阻断 C-4.3-03）→
 * 最低毛利硬阻断（BR-4.3-34 特批放行回写单号）→ 最后生效价裁决与价格审计（C-4.3-04/BR-4.3-35/36）。
 */
public interface PricingDiscountService {

    // ---------- 渠道折扣配置（5.2） ----------

    Page<DiscountChannel> channelPage(long current, long size, String channel);

    DiscountChannel saveChannel(DiscountChannel c);

    DiscountChannel stopChannel(String id, String reason);

    DiscountChannel enableChannel(String id);

    // ---------- 促销活动配置（5.4 配置侧） ----------

    Page<Promotion> promoPage(long current, long size, String keyword, String status);

    Promotion savePromotion(Promotion p);

    /** 发布（DRAFT/STOPPED → PUBLISHED） */
    Promotion publishPromotion(String id);

    Promotion stopPromotion(String id, String reason);

    // ---------- 折扣引擎（5.2~5.7） ----------

    /**
     * 单行计算：三层匹配 → 叠加 → 毛利阻断 → （可选）落审计。
     *
     * @param basePrice     协议基准价（调用方来自 trial 试算）
     * @param srcType       审计归属 SO/QUOTE/CALC（CALC 不落审计）
     * @param persistAudit  true 时写入价格审计日志
     * @return {channel,ladder,promo,stackMode,finalPrice,appliedSource,rejected,conflict,
     *         blocked,blockReason,margin:{cost,threshold,gap,blocked,specialNo},warnings}
     */
    Map<String, Object> calc(String customerId, String itemCode, BigDecimal qty,
                             BigDecimal basePrice, String srcType, String srcId,
                             Integer lineNo, boolean persistAudit);

    /**
     * 价格矩阵（5.8 / 3.6.1）：客户 × SKU 最终价与命中来源（只读，不落审计）。
     * basePrice 由协议试算接口实时取。
     */
    Map<String, Object> matrix(String customerId, List<String> itemCodes, BigDecimal qty);

    // ---------- 审计查询（5.7 / 3.6.4） ----------

    Page<PriceAudit> auditPage(long current, long size, String srcType, String srcId,
                               Integer lineNo, String operatorId,
                               LocalDate from, LocalDate to);

    // ---------- 特殊价格审批单（5.6，S-4.3-08） ----------

    Page<SpecialPrice> specialPage(long current, long size, String keyword, String status);

    SpecialPrice getSpecial(String id);

    /** 申请（DRAFT/PENDING 前置） */
    SpecialPrice applySpecial(SpecialPrice sp);

    /** 提交审批：销售总监 + 财务会签（同 SEQ 双节点） */
    SpecialPrice submitSpecial(String id);

    // ---------- 叠加规则参数（5.5） ----------

    /** 读取叠加模式（BEST_SINGLE / STACKED） */
    String getStackMode();

    /** 切换叠加模式（留痕参数历史） */
    void setStackMode(String mode, String operator);
}
