package com.erp.service.inv;

/**
 * 拣货差异过账门闩（spec picking-review 出库过账门闩与任务联动；BR-4.4-29 L1）。
 * 单一查询、四域入口消费（design D2：作业台委托四域自动继承，不重复挂）。
 * 挂载约定：过账事务内、状态校验后调用 assertClear；过账成功后调用 markCompleted。
 */
public interface PickTaskGate {

    /**
     * 该单据存在 DIFF_TYPE=PICK 且 PENDING 的差异行 → 422
     * 「存在未闭环拣货差异，禁止出库过账」，无任何库存与流水变动。
     * 无任务/无差异 → 放行（不影响存量单据行为）。
     */
    void assertClear(String srcType, String srcDocNo);
}
