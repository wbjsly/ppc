package com.erp.service.mdm;

import java.util.List;
import java.util.Map;

/**
 * 汇率批量更新业务能力契约（汇率管理 1.5.2，FR-4.1-3-3 + BR-4.1-19）。
 */
public interface MdmExchangeRateBatchService {

    /**
     * 行级预检（dry-run 不落库，design D2）：
     * 归一化公共来源编号 → 批内按（币对×类型）分组按生效日排序 →
     * 逐行字段校验（ExchangeRateRules.validateFields）+ 区间衔接（存量 ∪ 批内前序）。
     * 返回逐行 {rowNo, valid, reason, normalized}。
     */
    Map<String, Object> preview(List<Map<String, Object>> rows, String defaultSourceFileNo);

    /**
     * 按行独立提交（BR-4.1-19）：>500 行 422 整批拒；本方法不加 @Transactional
     * 使每行 create 各自独立事务提交；单行失败收集不回滚成功行。
     * 返回 {total, succeeded, failed, details[]}。
     */
    Map<String, Object> batch(List<Map<String, Object>> rows, String defaultSourceFileNo);
}
