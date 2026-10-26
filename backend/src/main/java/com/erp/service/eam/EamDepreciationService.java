package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamAssetChange;
import com.erp.entity.eam.EamDepreciationRun;

import java.util.Map;

/**
 * 折旧计提服务（add-asset-depreciation，spec asset-depreciation）。
 * 月度计提（PERIOD_TAG 幂等）→ 三方法计算 → 成本分摊 → 自动过账凭证 → 台账回写；
 * 另承载政策变更（下期生效不追溯）/ 减值 / 整单冲回三条变动处理链。
 */
public interface EamDepreciationService {

    /** 变动单分页（POLICY/IMPAIRMENT/REVERSE） */
    Page<EamAssetChange> pageChange(long current, long size, String changeType, String cardNo);

    /**
     * 政策变更提交（BR-4.13-14）：三政策字段 before/after 快照 → 审批底座单签
     * ROLE_FIN_ASSET；通过后置 APPROVED 待生效，下期计提读新政策，不追溯当期。
     */
    Map<String, Object> changePolicy(String cardId, Integer afterLifeYears,
                                     java.math.BigDecimal afterResidualRate,
                                     String afterMethod, String reason);

    /**
     * 政策变更提交（指定生效期间 yyyyMM；默认走 {@link #changePolicy} 的系统下期口径）。
     * BR-4.13-14：生效期间 MUST ≥ 下期，不得追溯当期。
     */
    Map<String, Object> changePolicy(String cardId, Integer afterLifeYears,
                                     java.math.BigDecimal afterResidualRate,
                                     String afterMethod, String reason, String effectivePeriod);

    /**
     * 审批通过回调落库：POLICY → 快照生效置 APPROVED（卡片政策待下期读取，不改本期）；
     * IMPAIRMENT → 减值凭证（借 EAM_DEPR_IMPAIR_CR / 贷 EAM_IMPAIRMENT_RESERVE）+
     * 卡片 IMPAIRMENT 落值（次月引擎以减值后 NBV 为基数重算，BR-4.13-15）。
     */
    Map<String, Object> approveChange(String changeId);

    /**
     * 减值提交（BR-4.13-15）：减值变更单 → 单签 ROLE_FIN_ASSET；
     * 通过出减值凭证（借 EAM_DEPR_IMPAIR_CR / 贷 EAM_IMPAIRMENT_RESERVE）+
     * 卡片 IMPAIRMENT 落值，次月引擎以减值后 NBV 为基数重算。
     */
    Map<String, Object> impairment(String cardId, java.math.BigDecimal amount, String reason);

    /** 明细按凭证反查（明细↔凭证双向） */
    java.util.List<com.erp.entity.fin.FinVoucherLine> detailsByVoucher(String voucherId);

    /** 审批驳回回调：置 REJECTED 留原因 */
    Map<String, Object> rejectChange(String changeId, String reason);

    /** 计提单分页 */
    Page<EamDepreciationRun> page(long current, long size, String periodTag);

    /** 计提单详情（含明细行） */
    Map<String, Object> detail(String runId);

    /**
     * 执行本月计提（同事务）：PERIOD_TAG 幂等卡控 → 应提清单 → 三方法计算 →
     * 成本分摊 → 自动过账凭证 → 台账回写（FR-4.6-5-1~7）。
     *
     * @param periodTag 会计月 yyyyMM
     */
    Map<String, Object> runDepreciation(String periodTag);

    /** 本期应提预览（未落库）：应提卡清单 + 预计折旧合计 + 参数缺失告警 */
    Map<String, Object> preview(String periodTag);

    /**
     * 整单冲回：红冲折旧凭证 + 明细标 REVERSED + 台账回滚 + 放行 PERIOD_TAG 重提。
     *
     * @param runId  计提单 ID
     * @param reason 冲回原因
     */
    Map<String, Object> reverse(String runId, String reason);
}
