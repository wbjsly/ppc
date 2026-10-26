package com.erp.service.proc;

import com.erp.entity.proc.OsAp;
import com.erp.entity.proc.OsSettlement;

import java.util.List;
import java.util.Map;

/**
 * 外协对账与应付挂账（change add-outsourcing-processing，spec outsourcing-settlement，菜单 5.10.4）。
 *
 * 偏差 D-OS-02：采购域无 AP 模块，本能力为最小对账-挂账承接（仅状态与金额，付款/核销留未来 AP 变更）。
 * 生成：按外协 PO（派工单 OUTSOURCE_FLAG 回联识别）汇总未对账收货过账行；
 *       金额 = 数量 × PO 成交价（GR 行快照），禁人工改价；同一收货行至多进一张对账单（唯一键防线）。
 * 状态机：DRAFT（可改可删）→ CONFIRMED（审批中）→ POSTED（挂账生成，不可改）/ REJECTED（驳回回 DRAFT）。
 * 审批：底座 BIZ_TYPE=OutsourceSettlement 单节点单签（ROLE_PLAN_MGR）；通过生成 OPEN 挂账，驳回释放引用。
 * 权限：写 = PLANNER/PLAN_MGR/PURCHASER/ADMIN（spec 菜单权限 5.10.4）。
 */
public interface OsSettlementService {

    /**
     * 按 PO 生成对账单（DRAFT）：取该 PO 全部未对账收货过账行（STATUS=POSTED），
     * 数量 = WITHIN_TOLERANCE_QTY（过账量）、单价 = GR 行 PO 成交价快照；
     * 无未对账明细 → 422；已引用行自动排除。
     *
     * @param poNo 外协采购订单号
     * @return 含行的完整对账单
     */
    OsSettlement generate(String poNo);

    /** 对账单列表（供应商/PO/状态筛选），创建时间倒序 */
    List<OsSettlement> list(String poNo, String supplierCode, String status);

    /** 对账单详情（头 + 行）；不存在 404 */
    Map<String, Object> detail(String id);

    /** 草稿删除（仅 DRAFT；CONFIRMED/POSTED/REJECTED 422） */
    void delete(String id);

    /**
     * 提交审批（DRAFT → CONFIRMED）：挂底座单节点单签（ROLE_PLAN_MGR）；
     * 无行 422；非 DRAFT 422（状态机守卫）。
     */
    OsSettlement submit(String id);

    // ---------- 回调驱动（ApprovalCallback 同事务调用） ----------

    /**
     * 审批通过（CONFIRMED → POSTED）：生成 OPEN 应付挂账（金额 = 对账单 TOTAL_AMT，
     * 来源对账单号唯一）+ POST_BY/POST_DATE；已 POSTED 幂等跳过。
     */
    OsSettlement approve(String id, String apprNo);

    /**
     * 审批驳回（CONFIRMED → DRAFT，spec「驳回回 DRAFT 可改」）：记录驳回原因；
     * 行随草稿保留（引用从审批锁释放回可编辑态，可改后重新提交）；
     * 彻底释放引用走 delete 草稿（行删后 GR_LINE_ID 唯一键释放，可重新汇总）。
     */
    OsSettlement reject(String id, String apprNo, String reason);

    /** 应付挂账列表（供应商/状态筛选） */
    List<OsAp> listAp(String supplierCode, String status);
}
