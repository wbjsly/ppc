package com.erp.service.proc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.proc.PurchaseOrder;

import java.util.List;
import java.util.Map;

/**
 * 采购订单能力契约（change add-framework-agreement-order，2.3.1~2.3.4，spec purchase-order）。
 * 三入口：从协议（带价锁定 + 余量校验）/ RFQ 中选（消费 awarded 桩）/ 手工；
 * 编号 PO + yyyyMM + 6 位流水；状态 DRAFT→APPROVING→APPROVED→CLOSED（审批动作见 PoApprovalService）。
 */
public interface PurchaseOrderService {

    /** 分页（状态/关键字/来源过滤） */
    Page<PurchaseOrder> page(long current, long size, String status, String source, String keyword);

    /** 详情：头 + 行（含协议/PR 溯源与价控结果） */
    Map<String, Object> detail(String id);

    /** 入口一：从协议下单（状态 ∈{1,2}、余量、锁价、同供应商、供应商合格；下达同事务回写余量） */
    Map<String, Object> createFromAgreement(Map<String, Object> payload);

    /** 入口二：RFQ 中选转 PO（消费 awarded(prNo) 桩，单价 = 中选价，PR 行回写 BR-4.2-51） */
    Map<String, Object> createFromRfq(String prNo);

    /** 入口三：手工创建（供应商合格 BR-4.2-18；价控组 7 接通） */
    Map<String, Object> createManual(Map<String, Object> payload);

    /** 提交审批（DRAFT → APPROVING）：先执行价控三重（组 7，BLOCK 时 422 提示特批），
     *  再按三档判级生成节点（ESCALATE 追加采购总监）。specialReason 非空 = 特批转升级链。 */
    Map<String, Object> submit(String id, String specialReason);

    /** 手工关闭（DRAFT/APPROVED → CLOSED，原因必填，偏差 D5） */
    Map<String, Object> close(String id, String reason);

    /**
     * 变更发起（FR-4.2-9-1，task 8.1/8.3）：仅 APPROVED 且无待审批变更；
     * 快照当前版本 → 应用新行集 → CURR_VERSION+1。金额未增：直接留痕（BR-4.2-04）；
     * 金额增加：PENDING + REQ_ROLE 分级（≤容差采购经理 / >容差采购总监 C-4.2-07）并重跑价控与预算（BR-4.2-42）。
     */
    Map<String, Object> change(String id, Map<String, Object> payload);

    /** 版本列表（变更类型/原因/审批状态/要求角色，倒序） */
    List<Map<String, Object>> versions(String id);

    /** 变更审批（FR-4.2-9-2）：通过置 APPROVED；驳回 → 自动回退至变更前状态（REJECTED + 回退留痕） */
    Map<String, Object> approveChange(String id, int versionNo, boolean approved, String reason);

    /** 按旧版本回滚（BR-4.2-03：回滚生成新版本，旧版本永久只读） */
    Map<String, Object> rollback(String id, int versionNo);

    /** 同步查询用：编号生成（PO + yyyyMM + 6 位） */
    String nextPoNo();

    /** 供审批/价控读取的行集 */
    List<Map<String, Object>> linesOf(String poId);
}
