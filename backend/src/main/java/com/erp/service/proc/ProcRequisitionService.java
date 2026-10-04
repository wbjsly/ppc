package com.erp.service.proc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.Map;

/**
 * 请购单业务能力契约（采购管理 2.1.1/2.1.2，FR-4.2-1-1 + BR-4.2-07/08/09 + C-4.2-11）。
 * 状态迁移一律经 ProcRequisitionSupport.transition（状态机单点）。
 */
public interface ProcRequisitionService {

    /** 分页（先执行懒 sweep：催办/升级标记 + 90 天自动关闭） */
    Page<Map<String, Object>> page(long current, long size, String status,
                                   String sourceType, String keyword);

    /** 详情：头 + 行（含交付计划行） */
    Map<String, Object> detail(String id);

    /** 手工创建（2.1.2）：预算三空 → PENDING_BUDGET（BR-4.2-09），否则 PENDING_APPROVAL */
    Map<String, Object> createManual(Map<String, Object> payload);

    /** 手工编辑（可改态：PENDING_BUDGET/PENDING_APPROVAL/PENDING_MODIFY；预算清空回落） */
    Map<String, Object> updateManual(String id, Map<String, Object> payload);

    /** 手工删除（仅可改态，软删 + 操作日志） */
    void deleteManual(String id);

    /** MRP 行编辑保存（PENDING_CONFIRM 态：数量/日期/调减理由/复核人） */
    void updateLines(String prId, List<Map<String, Object>> lines);

    /** 整单确认（PENDING_CONFIRM→CONFIRMED）：逐行 80% 卡控（BR-4.2-08） */
    Map<String, Object> confirm(String prId);

    /** 行补充/修改建议供应商（CONFIRMED/APPROVED 态） */
    void setLineSupplier(String lineId, String supplierId);

    /** 流转询价（APPROVED→PENDING_RFQ，须 ≥1 行供应商非空） */
    Map<String, Object> routeToRfq(String prId);

    /** 整单手工关闭（任意非 CLOSED，原因必填） */
    void closePr(String id, String reason);

    /** 行手工关闭（OPEN→CLOSED，原因必填；全行关闭 → 头 CLOSED） */
    void closeLine(String lineId, String reason);

    /** 交付计划行整单替换（Σqty ≤ 行需求；已关闭行禁维护） */
    List<Map<String, Object>> saveDeliveryLines(String lineId, List<Map<String, Object>> rows);

    /** PO 下达回写桩（BR-4.2-51：超量阻断；达量关行，全行关 → 头关） */
    Map<String, Object> receivePoAllocation(String lineId, java.math.BigDecimal qty);

    /**
     * MRP 生成（design D4）：一张 PR（sourceType=MRP、状态 PENDING_CONFIRM）承载已预检通过的行，
     * 行含 MRP 建议量基线（80% 卡控）、建议供应商、逾期标记；预估单价缺省 0（判级口径 design 记）。
     * 返回 {prId, prNo}。
     */
    Map<String, Object> createFromMrp(List<Map<String, Object>> acceptedRows);
}
