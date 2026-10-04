package com.erp.service.mdm;

import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.mdm.MdmCustomerViewVersion;

import java.util.List;
import java.util.Map;

/**
 * 信用额度业务能力契约（客户管理 1.3.2，FR-4.1-6-3 / BR-4.1-31/32/33 / C-4.3-13 台账）。
 */
public interface MdmCreditService {

    /**
     * 法人额度调整：常规额度（求和 422 复用）+ 临时额度（有效期必填 > 当天）；
     * 原因必填 → UPDATE 快照（含 diff）+ MDM.CUSTOMER.CREDIT_UPDATED 日志桩；已合并客户 422。
     */
    MdmCustomerView adjustViewLimit(MdmCustomerView incoming);

    /** 集团基准调整：总额度/评级（原因必填，UPDATE 快照；调整后重算占用率） */
    Map<String, Object> adjustGroupLimit(Map<String, String> payload);

    /** 占用率（BR-4.1-32）：Σ法人常规额度 ÷ 集团总额度；未配置返回未配置标记（不除零） */
    Map<String, Object> occupancy(String groupId);

    /** 复审通过（C-4.3-13）：更新 LAST_REVIEW_DATE=今天 + 清 COMPRESSED_LIMIT 恢复 → 快照 */
    MdmCustomerView reviewPassed(String viewId, String reason);

    /** 集团客户+法人视图额度概览（列表数据源：含有效额度、超期标记、临时到期信息） */
    Map<String, Object> overview(String keyword, String status, long current, long size);

    /** 额度时间轴：按集团聚合各法人视图的额度类快照（UPDATE/快照含额度 diff） */
    List<Map<String, Object>> timeline(String groupId);

    /** 可用额度试算（FR-4.3-2-2 公式 + 下游桩口径明示） */
    Map<String, Object> trial(String viewId);

    /**
     * 复审超期懒压缩（D5，幂等）：超期 && COMPRESSED_LIMIT IS NULL → 写 CREDIT_LIMIT×ratio；
     * 建档不足 12 个月宽限不压缩。返回本批压缩条数。
     */
    int compressOverdueReviews();

    /**
     * 临时额度到期回滚（BR-4.1-33，幂等）：TEMP_EXPIRE_DATE ≤ 今天 → 清临时两列 + 快照 + 日志桩。
     * 供 @Scheduled 与查询懒校验共用，返回本批回滚条数。
     */
    int sweepExpiredTemp();
}
