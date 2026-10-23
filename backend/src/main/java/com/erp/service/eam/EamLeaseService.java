package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamLeaseContract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 租赁全周期管理（add-asset-transfer-lease，spec asset-transfer-lease，IFRS 16 承租人视角）。
 * 合同录入 → 分类人工确认 → 使用权确认（PV+摊余计划+ROU 建卡）→ 月度处理（幂等）→
 * 变更重新计量（单签）→ 临期预警 → 到期终止（退租核销/购买转自有）。
 */
public interface EamLeaseService {

    Page<EamLeaseContract> page(long current, long size, String status, String keyword);

    Map<String, Object> detail(String id);

    /** 录入（关键条款完整性 422 逐项列示） */
    Map<String, Object> create(EamLeaseContract contract);

    Map<String, Object> update(EamLeaseContract contract);

    /** 删除（仅 DRAFT） */
    Map<String, Object> delete(String id);

    /** 系统辅助分类判断（≤12 月或低值 → EXEMPT 候选；否则 ROU 候选 + 依据留痕） */
    Map<String, Object> suggestClassify(String id);

    /** 分类人工确认（CLASSIFY + 依据 + 确认人；争议由财务经理确认留痕） */
    Map<String, Object> classify(String id, String classify, String basis, boolean disputeByFinMgr);

    /**
     * 使用权确认（BR-4.13-24/25）：
     * ROU → PV+初始费用+预付−激励 建卡（LEASE_FLAG）+ 全周期摊余计划；
     * EXEMPT → 不建卡不出计划，豁免留痕（L5）。
     */
    Map<String, Object> confirm(String id);

    /** 摊余计划查看（当前行 + 快照行） */
    Map<String, Object> schedule(String contractId);

    /**
     * 月度租赁处理（月标签幂等 422）：利息凭证 + 付款凭证（无付款行跳过）+
     * 豁免合同直线租金凭证；金额环比异常 L4 notice。
     */
    Map<String, Object> monthlyProcess(String periodTag);

    /** 整单冲回（红冲三类凭证 + 计划行复位 + 放行重提） */
    Map<String, Object> reverse(String periodTag);

    /** 变更重新计量提交（财务资产会计单签；通过后回调 {@link #executeRemeasure}） */
    Map<String, Object> remeasure(String contractId, BigDecimal revisedRate,
                                  Integer extraMonths, BigDecimal revisedPayment, String reason);

    /** 重新计量执行（回调入口：旧行快照归档 + 剩余行重算 + ROU 原值差额调整凭证） */
    Map<String, Object> executeRemeasure(String contractId);

    /** 临期清单（6 月/1 月分级 + 默示续租暂估标记，BR-4.13-44） */
    List<Map<String, Object>> expiryList();

    /**
     * 到期终止（BR-4.13-43）：type=RETURN 退租核销 / BUY 购买转自有；
     * 核销差异超容差 L1 阻断；damageAmount>0 非正常损坏赔偿挂应收。
     */
    Map<String, Object> terminate(String contractId, String type,
                                  BigDecimal damageAmount, String reason);
}
