package com.erp.service.pm;

import java.util.Map;

/** 财务成本结算（add-project-closure，FR-4.8-7-4 / BR-4.8-29）：确认+解冻+不可变快照。 */
public interface PmClosureSettlementService {

    /** 结算汇总（归集/基线/偏差/冻结状态，11.12.2 页展示） */
    Map<String, Object> summary(String projectId);

    /**
     * 财务确认结算：FINANCE/FIN_MGR 限权 → 解项目级成本冻结 → 生成不可变快照。
     * 已结算 409；不做账务结转。
     */
    Map<String, Object> confirm(String projectId);
}
