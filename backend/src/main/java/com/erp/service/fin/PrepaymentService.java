package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.FinPrepayment;

import java.util.List;
import java.util.Map;

/**
 * 预付款（2.7.3，spec prepayment，BR-4.2-52 / C-4.2-13）。
 * 创建双 L1 校验（比例 / 累计 vs PO 未清）→ 分级审批（PrepaymentApprovalCallback）→
 * 预付执行（PPR 凭证）→ 到货开票后按最早未核销顺序冲抵应付（SETTLE）。
 */
public interface PrepaymentService {

    /**
     * 创建预付款申请：比例取 PO.PREPAY_RATIO（空则默认参数），双 L1 校验——
     * ① 金额 > PO 总额 × 比例 422；② 累计预付（含本次）> PO 未清金额 422。
     * 冻结供应商与 FROZEN/DISABLED 供应商 422。
     */
    FinPrepayment create(Map<String, Object> payload);

    /** 提交分级审批（复用 payment-request 的金额分档链） */
    FinPrepayment submit(String id);

    /** 作废（仅 DRAFT/REJECTED，原因必填） */
    FinPrepayment cancel(String id, String reason);

    /** 预付执行（仅 ADMIN，状态 APPROVED）：余额条件扣减 + PPR 凭证（借 1123 / 贷 1002） */
    Map<String, Object> execute(Map<String, Object> payload);

    /**
     * 到货开票后冲抵应付（BR-4.2-52）：该 PO 未清发票按 invoiceDate 升序 ×
     * 预付单按日期升序（最早未核销顺序）逐笔 MIN(余额, 未清) 冲抵，
     * 生成 KIND=SETTLE 记录 + PPO 凭证（借 2202 / 贷 1123）。幂等：已冲组合跳过。
     *
     * @return 本次冲抵笔数（0 = 无可冲抵）
     */
    int settleForPo(String poNo);

    /** 预付申请分页 */
    Page<Map<String, Object>> page(long current, long size, String supplierId, String poNo, String status);

    /** 预付详情（含付款与冲抵记录） */
    Map<String, Object> detail(String id);

    /** 预付款清理待办：冻结供应商 + 未核销预付清单（异常表 C-4.2-13/C-4.2-02） */
    List<Map<String, Object>> cleanupTodos();
}
