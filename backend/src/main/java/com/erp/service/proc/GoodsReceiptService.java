package com.erp.service.proc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 收货管理契约（2.4 全组，change add-goods-receipt）：
 * 2.4.1 登记（PO 默认 + FREE 无 PO）、2.4.3 容差差异与调整单（BR-4.2-21/22/49）、
 * 2.4.2 待检与放行（FR-4.2-4-2）、2.4.4 过账（FR-4.2-6-1 / BR-4.2-28）。
 */
public interface GoodsReceiptService {

    // ---------- 2.4.1 登记 ----------

    /** 可收货 PO 候选（APPROVED，含供应商与已收摘要） */
    Map<String, Object> poCandidates();

    /** PO 未清行预填（qty − receivedQty） */
    Map<String, Object> poLines(String poId);

    /** 按 PO 登记：未清行 + 实收 + 容差分流 + 差异单生成（spec goods-receipt 双入口之默认） */
    Map<String, Object> createByPo(Map<String, Object> payload);

    /** 无 PO 登记（FREE，免容差扩展场景） */
    Map<String, Object> createFree(Map<String, Object> payload);

    Page<Map<String, Object>> page(long current, long size, String status,
                                   String sourceType, String keyword);

    Map<String, Object> detail(String id);

    /** 作废（仅 CREATED，原因必填） */
    Map<String, Object> cancel(String id, String reason);

    // ---------- 2.4.3 差异与调整单 ----------

    Page<Map<String, Object>> differencePage(long current, long size,
                                             String status, String diffType, String keyword);

    /**
     * 差异处置（采购员，ADMIN）：action = START_ADJUST（发起调整单）| REJECT（拒收）| CLOSE（手动关闭，短交用）
     * START_ADJUST 返回 adjustmentId，进入调整单审批。
     */
    Map<String, Object> disposeDifference(String diffId, String action, String note);

    Page<Map<String, Object>> adjustmentPage(long current, long size, String status);

    /** 调整单发起：diffId（超交差异转调整）或 poLineId+addQty（迟到货物，L1057）；创建即 PENDING_APPROVE */
    Map<String, Object> createAdjustment(Map<String, Object> payload);

    /** 调整单单节点批准（采购经理岗 ADMIN/PM）；通过即同事务执行（BR-4.2-49） */
    Map<String, Object> approveAdjustment(String adjId, boolean approved, String note);

    // ---------- 2.4.2 待检 ----------

    /**
     * 待检看板（2.4.2）：数据源 = 检验批（偏差 D1：起算点为 GR 登记提交时间）。
     * 人工兜底放行接口已随 2.5 接入移除（qc-hold-area REMOVED），放行动作归检验批确认合格。
     */
    Page<Map<String, Object>> qcPage(long current, long size);

    // ---------- 2.4.4 过账 ----------

    /** 入库过账（单事务五步，spec receipt-posting） */
    Map<String, Object> posting(String grId);

    // ---------- 供前端选择器复用 ----------

    /** 物料风险等级编辑入口（4.3，默认 C）——委托 MdmItem，此处仅暴露读 */
    List<Map<String, Object>> riskGradeOptions();
}
