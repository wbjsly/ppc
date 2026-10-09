package com.erp.service.inv;

import com.erp.entity.inv.InvFreeze;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 库存冻结/解冻双轨管理（spec freeze-management，FR-4.4-5 八步 SOP / C-4.4-05）。
 * 审批底座 BIZ_TYPE：冻结 = "Freeze"，解冻 = "Unfreeze"（独立审批 FR-4.4-5-7）。
 * 角色分流：质量 QUALITY_ENG 发起 → QUALITY_MGR 审；财务 ROLE_FINANCE 发起 → FINANCE_MGR 审。
 */
public interface FreezeService {

    String BIZ_FREEZE = "Freeze";
    String BIZ_UNFREEZE = "Unfreeze";

    /**
     * 冻结申请：类型/原因/数量校验（422）+ 发起角色分流（403）+ 挂单节点审批（PENDING）。
     * 同一业务重复提交审批由审批底座 422 拒绝。
     */
    InvFreeze apply(InvFreeze req);

    /**
     * 解冻申请（FR-4.4-5-6/7）：仅原发起人（403）、仅生效中 MANUAL 冻结（422）、
     * 处理结果与依据必填（422）、独立审批（同类型审批角色）。
     */
    InvFreeze applyUnfreeze(String id, String releaseResult, String releaseBasis);

    /** 冻结库存查询（4.3.2）：类型/状态/物料/批次/关键字筛选，NCR 来源只读标识 */
    List<Map<String, Object>> query(String freezeType, String status, String itemCode,
                                    String batchNo, String keyword);

    /**
     * NCR 流程冻结/解冻的台账归集（spec ncr-management MODIFIED，来源=NCR 立即生效无审批）。
     * 解冻仅释放该 NCR 自有台账行（不误放手工冻结行）。
     */
    void recordNcrFreeze(String warehouseCode, String itemCode, String batchNo,
                         BigDecimal qty, String ncrId);

    void recordNcrUnfreeze(String warehouseCode, String itemCode, String batchNo,
                           BigDecimal qty, String ncrId);
}
