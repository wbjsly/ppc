package com.erp.service.mrp;

import com.erp.entity.fin.FinVoucher;

import java.util.List;
import java.util.Map;

/**
 * 完工入库凭证（change add-cost-aggregation，spec completion-cost-voucher）。
 * 标准成本法三段结构：借产成品=标准×合格产出、贷生产成本三明细=实际、差额行入
 * 生产成本差异自动判向补平；生成即 POSTED（L1 卡控），会计期间已关 → DRAFT 待下期过账。
 */
public interface CostVoucherService {

    /** 凭证来源类型（业财锚点：按工单号反查） */
    String SOURCE_TYPE = "MO_COMPLETE";
    /** 完工凭证类型前缀（凭证号 CVC+年月+流水） */
    String TYPE_CVC = "CVC";

    /**
     * 完工归集后生成凭证（幂等锚 = SOURCE_TYPE + 工单号；重复调用/手动重跑不再生成）。
     * 全零金额不生成；科目/平衡/法人币种 L1 卡控由 gl-voucher 统一把守（失败 422 不落库）。
     * 会计期间已关（COST_CLOSED_PERIODS 命中）→ 落 DRAFT 并提示下期过账。
     * summary 由钩子从 aggregate 结果传入（本方法 REQUIRES_NEW 独立事务，不回读
     * 完工事务未提交数据——goodQty=0 连锁错误的教训）。
     *
     * @return 生成的凭证；幂等跳过或零金额返回 null
     */
    FinVoucher generateOnAggregation(String moId, com.erp.entity.mrp.CostSummary summary);

    /** 完工凭证列表（status/period 筛选；来源=MO_COMPLETE） */
    List<Map<String, Object>> list(String status, String period);

    /** 凭证头行明细（复用 gl-voucher detail） */
    Map<String, Object> detail(String voucherId);

    /** 待过账（DRAFT）完工凭证清单（5.11.4） */
    List<Map<String, Object>> draftList();

    /**
     * DRAFT → POSTED（期间开启后下期过账动作）。仅限完工来源凭证；
     * 写权限 COST_ACCOUNTANT/ADMIN（FINANCE 只读，spec 场景）。
     */
    void postDraft(String voucherId);

    /**
     * 月度差异转销（BR-4.5-29）：汇总指定期次完工凭证的生产成本差异行净额，
     * 生成转销凭证（默认转销售成本 6401）并 POSTED。
     * 同期次重复转销 422（幂等锚 = 来源单号=期次）；净额为 0 不生成返回 null。
     *
     * @param period yyyyMM
     */
    FinVoucher transferVariance(String period);
}
